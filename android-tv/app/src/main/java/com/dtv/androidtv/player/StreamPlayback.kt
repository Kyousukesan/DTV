package com.dtv.androidtv.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.dtv.androidtv.data.douyu.DouyuConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/**
 * Media3 playback helper for Douyu FLV (+ test HLS).
 *
 * Douyu FLV continuity (LT-5):
 * - Primary: localhost [DouyuFlvRelayServer] splices FLV tags across ~300s CDN cuts
 *   (port of desktop StreamRelayService / flv_relay). Media3 plays one continuous URL.
 * - Fallback: quiet MediaItem re-sign (~240s) + error backoff if relay prepare fails.
 */
@UnstableApi
class StreamPlayback(
    context: Context,
    private val scope: CoroutineScope,
    private val resolveUrl: suspend (quality: String, line: String) -> String,
) {
    private val appContext = context.applicationContext

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(DouyuConstants.DESKTOP_UA)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(0)

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
        .build()

    /**
     * Local splice relay. Resolve uses the ViewModel-bound [resolveUrl]
     * (room id is closed over there); session still stores room/quality/line.
     */
    private val relayServer = DouyuFlvRelayServer { _, quality, line ->
        runBlocking { resolveUrl(quality, line) }
    }

    private var renewJob: Job? = null
    private var roomId: String = ""
    private var quality: String = ""
    private var line: String = ""
    private var format: String = "flv"
    private var usingRelay: Boolean = false
    private val consecutiveErrors = AtomicInteger(0)

    var onError: ((String) -> Unit)? = null
    var onPlaying: (() -> Unit)? = null
    var onIsPlayingChanged: ((Boolean) -> Unit)? = null
    var onRelayEvent: ((String) -> Unit)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                scope.launch { handlePlaybackError(error) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    consecutiveErrors.set(0)
                    onPlaying?.invoke()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onIsPlayingChanged?.invoke(isPlaying)
            }
        })
    }

    fun play(
        roomId: String,
        url: String,
        format: String,
        quality: String,
        line: String,
    ) {
        this.roomId = roomId
        this.quality = quality
        this.line = line
        this.format = format
        consecutiveErrors.set(0)
        renewJob?.cancel()
        usingRelay = false

        if (!format.equals("hls", true) && roomId.isNotBlank() && roomId != "test") {
            try {
                val prepared = relayServer.prepare(roomId, quality, line)
                usingRelay = true
                onRelayEvent?.invoke("relay ${prepared.sessionId} → ${prepared.localUrl}")
                Log.i(TAG, "Using FLV splice relay ${prepared.localUrl}")
                setMedia(prepared.localUrl, format = "flv", isLocalRelay = true)
                startRelayWatchdog()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Relay prepare failed, falling back to direct FLV: ${e.message}")
                onRelayEvent?.invoke("relay_prepare_failed: ${e.message}")
            }
        }

        setMedia(url, format, isLocalRelay = false)
        startRenewLoop()
    }

    fun playTestHls() {
        roomId = "test"
        quality = ""
        line = ""
        format = "hls"
        usingRelay = false
        renewJob?.cancel()
        consecutiveErrors.set(0)
        setMedia(DouyuConstants.TEST_HLS_URL, "hls", isLocalRelay = false)
    }

    fun release() {
        renewJob?.cancel()
        player.release()
        relayServer.shutdown()
    }

    fun relayStats(): FlvRelayStats? = relayServer.lastRelayStats()

    private fun setMedia(url: String, format: String, isLocalRelay: Boolean) {
        val headers = mutableMapOf(
            "User-Agent" to DouyuConstants.DESKTOP_UA,
            "Accept" to "video/x-flv,application/octet-stream,*/*",
            "Connection" to "keep-alive",
        )
        if (!isLocalRelay && roomId.isNotBlank() && roomId != "test") {
            headers["Referer"] = "https://www.douyu.com/$roomId"
        }
        httpFactory.setDefaultRequestProperties(headers)

        val itemBuilder = MediaItem.Builder().setUri(url)
        val mediaSource = when {
            format.equals("hls", true) || url.contains(".m3u8") -> {
                itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                HlsMediaSource.Factory(httpFactory).createMediaSource(itemBuilder.build())
            }
            else -> {
                itemBuilder.setMimeType(MimeTypes.VIDEO_FLV)
                ProgressiveMediaSource.Factory(httpFactory)
                    .createMediaSource(itemBuilder.build())
            }
        }
        player.setMediaSource(mediaSource, /* resetPosition= */ true)
        player.prepare()
        player.playWhenReady = true
        player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
    }

    /** Relay path: Media3 stays on one URL; log splice stats periodically. */
    private fun startRelayWatchdog() {
        renewJob?.cancel()
        renewJob = scope.launch {
            while (isActive) {
                delay(30_000L)
                val stats = relayServer.lastRelayStats()
                if (stats != null) {
                    onRelayEvent?.invoke(
                        "relay bytes=${stats.bytesOut.get()} switches=${stats.segmentSwitches.get()} " +
                            "event=${stats.lastEvent}",
                    )
                }
            }
        }
    }

    private fun startRenewLoop() {
        renewJob?.cancel()
        if (format.equals("hls", true) || roomId == "test" || usingRelay) return
        renewJob = scope.launch {
            while (isActive) {
                delay(RENEW_INTERVAL_MS)
                retryWithFreshUrl(reason = "scheduled_resign", surfaceError = false)
            }
        }
    }

    private suspend fun handlePlaybackError(error: PlaybackException) {
        if (roomId.isBlank() || roomId == "test") {
            onError?.invoke(error.message ?: error.errorCodeName)
            return
        }
        val attempt = consecutiveErrors.incrementAndGet()
        if (attempt <= MAX_ERROR_RETRIES) {
            delay(RETRY_BACKOFF_MS * attempt)
            if (usingRelay) {
                try {
                    val prepared = relayServer.prepare(roomId, quality, line)
                    setMedia(prepared.localUrl, format = "flv", isLocalRelay = true)
                    consecutiveErrors.set(0)
                    onRelayEvent?.invoke("relay_reprepare after error")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "relay reprepare failed: ${e.message}")
                }
            }
            retryWithFreshUrl(reason = "playback_error_$attempt", surfaceError = false)
            return
        }
        onError?.invoke(error.message ?: error.errorCodeName)
    }

    private suspend fun retryWithFreshUrl(reason: String, surfaceError: Boolean) {
        if (roomId.isBlank() || roomId == "test") return
        try {
            val fresh = resolveUrl(quality, line)
            usingRelay = false
            setMedia(fresh, format, isLocalRelay = false)
            consecutiveErrors.set(0)
        } catch (e: Exception) {
            if (surfaceError) {
                onError?.invoke("续流失败($reason): ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "StreamPlayback"
        const val RENEW_INTERVAL_MS = 240_000L
        const val MAX_ERROR_RETRIES = 3
        const val RETRY_BACKOFF_MS = 1_200L
    }
}

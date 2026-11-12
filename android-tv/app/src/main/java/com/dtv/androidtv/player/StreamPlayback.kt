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
import com.dtv.androidtv.data.model.LivePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/**
 * Media3 playback helper for Douyu / Huya / Douyin / Bilibili.
 *
 * - Douyu FLV: localhost [DouyuFlvRelayServer] splice across ~300s CDN cuts.
 * - Huya / Douyin FLV: direct progressive (+ quiet re-sign fallback); no Douyu relay.
 * - Bilibili: HLS via [HlsMediaSource] (desktop also prefers HLS).
 */
@UnstableApi
class StreamPlayback(
    context: Context,
    private val scope: CoroutineScope,
    private val platformId: String,
    private val resolveUrl: suspend (quality: String, line: String) -> String,
) {
    private val appContext = context.applicationContext
    private val platform = LivePlatform.fromId(platformId)

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(userAgentFor(platform))
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(0)

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
        .build()

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

        val wantsFlv = !format.equals("hls", true) && !url.contains(".m3u8")
        // Local splice relay is Douyu-specific (signed CDN cut ~300s).
        if (wantsFlv && platform == LivePlatform.DOUYU && roomId.isNotBlank() && roomId != "test") {
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
        if (wantsFlv) startRenewLoop()
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
            "User-Agent" to userAgentFor(platform),
            "Accept" to "*/*",
            "Connection" to "keep-alive",
        )
        if (!isLocalRelay && roomId.isNotBlank() && roomId != "test") {
            refererFor(platform, roomId)?.let { headers["Referer"] = it }
        }
        httpFactory.setDefaultRequestProperties(headers)
        httpFactory.setUserAgent(userAgentFor(platform))

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
            if (usingRelay && platform == LivePlatform.DOUYU) {
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

        private fun userAgentFor(platform: LivePlatform): String =
            when (platform) {
                LivePlatform.DOUYU -> DouyuConstants.DESKTOP_UA
                LivePlatform.HUYA ->
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:123.0) Gecko/20100101 Firefox/123.0"
                LivePlatform.DOUYIN ->
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                LivePlatform.BILIBILI ->
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            }

        private fun refererFor(platform: LivePlatform, roomId: String): String? =
            when (platform) {
                LivePlatform.DOUYU -> "https://www.douyu.com/$roomId"
                LivePlatform.HUYA -> "https://www.huya.com/$roomId"
                LivePlatform.DOUYIN -> "https://live.douyin.com/$roomId"
                // Bilibili HLS typically works without Referer; FLV needs a proxy we skip.
                LivePlatform.BILIBILI -> "https://live.bilibili.com/$roomId"
            }
    }
}

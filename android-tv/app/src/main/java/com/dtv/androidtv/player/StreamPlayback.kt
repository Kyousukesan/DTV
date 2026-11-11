package com.dtv.androidtv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.dtv.androidtv.data.douyu.DouyuClient
import com.dtv.androidtv.data.douyu.DouyuConstants
import com.dtv.androidtv.data.net.HttpJsonClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

/**
 * Media3 playback helper for Douyu FLV (+ test HLS).
 *
 * FLV path: ProgressiveMediaSource + FlvExtractor.
 * Stability (M3 polish, still no full splice-relay):
 * - `expire=0` patch for ws CDN 300s links
 * - scheduled re-sign ~240s
 * - on error: backoff retries with fresh URL before surfacing UI error
 */
@UnstableApi
class StreamPlayback(
    context: Context,
    private val scope: CoroutineScope,
    private val resolveUrl: suspend (quality: String, line: String) -> String,
) {
    private val appContext = context.applicationContext

    private val okHttp: OkHttpClient = HttpJsonClient.defaultClient().newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val httpFactory = OkHttpDataSource.Factory(okHttp)
        .setUserAgent(DouyuConstants.DESKTOP_UA)

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
        .build()

    private var renewJob: Job? = null
    private var retryJob: Job? = null
    private var roomId: String = ""
    private var quality: String = ""
    private var line: String = ""
    private var format: String = "flv"
    private val consecutiveErrors = AtomicInteger(0)

    var onError: ((String) -> Unit)? = null
    var onPlaying: (() -> Unit)? = null
    var onIsPlayingChanged: ((Boolean) -> Unit)? = null

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
        setMedia(url, format, roomId)
        startRenewLoop()
    }

    fun playTestHls() {
        roomId = "test"
        quality = ""
        line = ""
        format = "hls"
        renewJob?.cancel()
        retryJob?.cancel()
        consecutiveErrors.set(0)
        setMedia(DouyuConstants.TEST_HLS_URL, "hls", roomId = "")
    }

    fun release() {
        renewJob?.cancel()
        retryJob?.cancel()
        player.release()
    }

    private fun setMedia(url: String, format: String, roomId: String) {
        val headers = mutableMapOf(
            "User-Agent" to DouyuConstants.DESKTOP_UA,
            "Accept" to "video/x-flv,application/octet-stream,*/*",
            "Connection" to "keep-alive",
        )
        if (roomId.isNotBlank()) {
            headers["Referer"] = DouyuClient.refererFor(roomId)
        }
        httpFactory.setDefaultRequestProperties(headers)

        val patched = DouyuClient.applyExpirePatch(url)
        val itemBuilder = MediaItem.Builder().setUri(patched)
        val mediaSource = when {
            format.equals("hls", true) || patched.contains(".m3u8") -> {
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

    private fun startRenewLoop() {
        renewJob?.cancel()
        if (format.equals("hls", true) || roomId == "test") return
        renewJob = scope.launch {
            while (isActive) {
                delay(RENEW_INTERVAL_MS)
                // Quiet re-sign: failures do not wipe the current buffer until retries exhaust.
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
            retryWithFreshUrl(reason = "playback_error_$attempt", surfaceError = false)
            return
        }
        onError?.invoke(error.message ?: error.errorCodeName)
    }

    private suspend fun retryWithFreshUrl(reason: String, surfaceError: Boolean) {
        if (roomId.isBlank() || roomId == "test") return
        try {
            val fresh = resolveUrl(quality, line)
            setMedia(fresh, format, roomId)
            consecutiveErrors.set(0)
        } catch (e: Exception) {
            if (surfaceError) {
                onError?.invoke("续流失败($reason): ${e.message}")
            }
            // Scheduled resign: leave current segment playing; next tick retries.
        }
    }

    companion object {
        /** Leave ~60s margin under Douyu's common 300s anonymous cut. */
        const val RENEW_INTERVAL_MS = 240_000L
        const val MAX_ERROR_RETRIES = 3
        const val RETRY_BACKOFF_MS = 1_200L
    }
}

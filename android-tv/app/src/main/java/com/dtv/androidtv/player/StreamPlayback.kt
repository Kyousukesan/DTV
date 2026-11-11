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
import java.util.concurrent.TimeUnit

/**
 * Media3 playback helper for Douyu FLV (+ test HLS).
 *
 * Douyu path notes (Slice-1):
 * - getH5PlayV1 returns progressive **FLV** (not HLS). Media3 plays it via
 *   [ProgressiveMediaSource] + built-in FlvExtractor — no FFmpeg extension required.
 * - CDN links often carry `expire=300&fcdn=ws`. We keep Go's `expire=0` patch and
 *   additionally **re-sign** (~240s) by re-calling stream resolve and swapping the
 *   MediaItem. Full local FLV splice-relay (desktop StreamRelayService) is deferred;
 *   re-sign + error retry is the practical continuous-watch path here.
 */
@UnstableApi
class StreamPlayback(
    context: Context,
    private val scope: CoroutineScope,
    private val resolveUrl: suspend (quality: String, line: String) -> String,
) {
    private val appContext = context.applicationContext

    private val okHttp: OkHttpClient = HttpJsonClient.defaultClient().newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // live streams are long-lived
        .build()

    private val httpFactory = OkHttpDataSource.Factory(okHttp)
        .setUserAgent(DouyuConstants.DESKTOP_UA)

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
        .build()

    private var renewJob: Job? = null
    private var roomId: String = ""
    private var quality: String = ""
    private var line: String = ""
    private var format: String = "flv"

    var onError: ((String) -> Unit)? = null
    var onPlaying: (() -> Unit)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onError?.invoke(error.message ?: error.errorCodeName)
                scope.launch { retryWithFreshUrl("playback_error") }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    onPlaying?.invoke()
                }
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
        setMedia(url, format, roomId)
        startRenewLoop()
    }

    fun playTestHls() {
        roomId = "test"
        quality = ""
        line = ""
        format = "hls"
        renewJob?.cancel()
        setMedia(DouyuConstants.TEST_HLS_URL, "hls", roomId = "")
    }

    fun release() {
        renewJob?.cancel()
        player.release()
    }

    private fun setMedia(url: String, format: String, roomId: String) {
        val headers = mutableMapOf(
            "User-Agent" to DouyuConstants.DESKTOP_UA,
            "Accept" to "*/*",
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
                // FLV progressive — Media3 FlvExtractor handles Douyu live FLV.
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

    /**
     * Re-resolve before the typical ~300s CDN cut. Swap MediaItem without leaving
     * the player screen; on failure surface error for UI retry.
     */
    private fun startRenewLoop() {
        renewJob?.cancel()
        if (format.equals("hls", true) || roomId == "test") return
        renewJob = scope.launch {
            while (isActive) {
                delay(RENEW_INTERVAL_MS)
                retryWithFreshUrl("scheduled_resign")
            }
        }
    }

    private suspend fun retryWithFreshUrl(reason: String) {
        if (roomId.isBlank() || roomId == "test") return
        try {
            val fresh = resolveUrl(quality, line)
            setMedia(fresh, format, roomId)
        } catch (e: Exception) {
            onError?.invoke("续流失败($reason): ${e.message}")
        }
    }

    companion object {
        /** Leave ~60s margin under Douyu's common 300s anonymous cut. */
        const val RENEW_INTERVAL_MS = 240_000L
    }
}

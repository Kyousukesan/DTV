package com.dtv.androidtv.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtv.androidtv.data.model.DanmakuMessage
import com.dtv.androidtv.data.model.DanmakuMode

/** TV-tuned defaults: readable at 10-foot, density-capped. */
object DanmakuTvDefaults {
    const val AREA_FRACTION = 0.55f
    const val OPACITY = 0.95f
    const val MAX_ACTIVE = 28
    const val SCROLL_DURATION_MS = 11_000L
    const val FIXED_DURATION_MS = 4_500L
    /** Larger than phone UI so bullets read from a sofa. */
    val FontSize = 28.sp
    val TrackGap = 6.dp
}

@Immutable
private data class ActiveDanmaku(
    val message: DanmakuMessage,
    val mode: DanmakuMode,
    val track: Int,
    val bornMs: Long,
    val durationMs: Long,
    val color: Color,
)

/**
 * Lightweight Compose danmaku layer (scroll / top / bottom).
 *
 * Approximate lane collision; not a full danmu.js port.
 *
 * Admission uses [snapshotFlow] so rapid StateFlow emits cannot cancel a batch
 * mid-way (unlike `LaunchedEffect(messages)`), which previously marked ids as
 * seen without ever drawing them.
 */
@Composable
fun DanmakuOverlay(
    messages: List<DanmakuMessage>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return

    val active = remember { mutableStateListOf<ActiveDanmaku>() }
    val seen = remember { mutableSetOf<String>() }
    val trackFreeAt = remember { LongArray(16) { 0L } }
    // Frame clock forces scroll offsets to recompose every vsync.
    var frameNanos by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        snapshotFlow { messages }.collect { list ->
            if (list.isEmpty()) {
                // Room reload / toggle clears the ring buffer — reset lanes too.
                seen.clear()
                active.clear()
                trackFreeAt.fill(0L)
                return@collect
            }
            if (seen.size > 500) {
                val drop = seen.toList().take(seen.size - 400)
                drop.forEach { seen.remove(it) }
            }
            val now = System.currentTimeMillis()
            val admitted = DanmakuAdmission.admit(
                messages = list,
                seen = seen,
                activeCount = active.size,
                maxActive = DanmakuTvDefaults.MAX_ACTIVE,
                trackFreeAt = trackFreeAt,
                nowMs = now,
            )
            for (c in admitted) {
                active += ActiveDanmaku(
                    message = c.message,
                    mode = c.mode,
                    track = c.track,
                    bornMs = now,
                    durationMs = c.durationMs,
                    color = parseDanmakuColor(c.message.color),
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameNanos = it }
            val now = System.currentTimeMillis()
            active.removeAll { now - it.bornMs >= it.durationMs }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val areaHeight = maxHeight * DanmakuTvDefaults.AREA_FRACTION
        val trackHeight = with(density) {
            DanmakuTvDefaults.FontSize.toDp() + DanmakuTvDefaults.TrackGap
        }
        val trackCount = ((areaHeight / trackHeight).toInt()).coerceIn(4, trackFreeAt.size)
        // Read frameNanos so scroll bullets invalidate each frame.
        @Suppress("UNUSED_EXPRESSION")
        frameNanos

        for (item in active.toList()) {
            key(item.message.id) {
                when (item.mode) {
                    DanmakuMode.SCROLL -> ScrollBullet(
                        item = item,
                        track = item.track.coerceAtMost(trackCount - 1),
                        trackHeight = trackHeight,
                        screenWidth = maxWidth,
                    )
                    DanmakuMode.TOP -> FixedBullet(
                        item = item,
                        alignment = Alignment.TopCenter,
                        paddingTop = 12.dp,
                    )
                    DanmakuMode.BOTTOM -> FixedBullet(
                        item = item,
                        alignment = Alignment.BottomCenter,
                        paddingBottom = 72.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScrollBullet(
    item: ActiveDanmaku,
    track: Int,
    trackHeight: Dp,
    screenWidth: Dp,
) {
    val progress = ((System.currentTimeMillis() - item.bornMs).toFloat() / item.durationMs)
        .coerceIn(0f, 1f)
    val travel = screenWidth + 480.dp
    val x = screenWidth - travel * progress
    val y = trackHeight * track + 8.dp

    DanmakuText(
        text = item.message.content,
        color = item.color,
        modifier = Modifier.offset {
            IntOffset(x.roundToPx(), y.roundToPx())
        },
    )
}

@Composable
private fun FixedBullet(
    item: ActiveDanmaku,
    alignment: Alignment,
    paddingTop: Dp = 0.dp,
    paddingBottom: Dp = 0.dp,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = paddingTop, bottom = paddingBottom),
        contentAlignment = alignment,
    ) {
        DanmakuText(
            text = item.message.content,
            color = item.color,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(horizontal = 24.dp),
        )
    }
}

/**
 * Use [BasicText] (not TV Material3 Text) so color/shadow are not overridden by
 * theme LocalContentColor on Leanback devices.
 */
@Composable
private fun DanmakuText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = color.copy(alpha = DanmakuTvDefaults.OPACITY),
            fontSize = DanmakuTvDefaults.FontSize,
            fontWeight = FontWeight.Bold,
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.9f),
                blurRadius = 6f,
            ),
        ),
        modifier = modifier,
    )
}

private fun parseDanmakuColor(raw: String): Color {
    val v = raw.trim()
    if (!v.startsWith("#") || (v.length != 7 && v.length != 4)) {
        return Color.White
    }
    return try {
        Color(android.graphics.Color.parseColor(v))
    } catch (_: Exception) {
        Color.White
    }
}

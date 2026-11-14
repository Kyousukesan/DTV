package com.dtv.androidtv.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.dtv.androidtv.data.model.DanmakuKind
import com.dtv.androidtv.data.model.DanmakuMessage
import com.dtv.androidtv.data.model.DanmakuMode

/** TV-tuned defaults: readable at 10-foot, density-capped. */
object DanmakuTvDefaults {
    const val AREA_FRACTION = 0.55f
    const val OPACITY = 0.92f
    const val MAX_ACTIVE = 28
    const val SCROLL_DURATION_MS = 11_000L
    const val FIXED_DURATION_MS = 4_500L
    val FontSize = 22.sp
    val TrackGap = 4.dp
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
 */
@OptIn(ExperimentalTvMaterial3Api::class)
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

    LaunchedEffect(messages) {
        val now = System.currentTimeMillis()
        for (msg in messages) {
            if (!seen.add(msg.id)) continue
            if (seen.size > 500) {
                val drop = seen.toList().take(seen.size - 400)
                drop.forEach { seen.remove(it) }
            }
            if (active.size >= DanmakuTvDefaults.MAX_ACTIVE) continue
            // Match desktop DanmuOverlay (chat-only on video); skip ENTER/gift clutter.
            when (msg.kind) {
                DanmakuKind.GIFT, DanmakuKind.ENTER -> continue
                DanmakuKind.CHAT, DanmakuKind.SYSTEM -> Unit
            }
            val mode = when (msg.kind) {
                DanmakuKind.SYSTEM -> DanmakuMode.TOP
                else -> msg.mode
            }
            val track = if (mode == DanmakuMode.SCROLL) {
                allocateTrack(trackFreeAt, now) ?: continue
            } else {
                0
            }
            val duration = if (mode == DanmakuMode.SCROLL) {
                DanmakuTvDefaults.SCROLL_DURATION_MS
            } else {
                DanmakuTvDefaults.FIXED_DURATION_MS
            }
            if (mode == DanmakuMode.SCROLL) {
                trackFreeAt[track] = now + (duration * 0.35).toLong()
            }
            active += ActiveDanmaku(
                message = msg,
                mode = mode,
                track = track,
                bornMs = now,
                durationMs = duration,
                color = parseDanmakuColor(msg.color),
            )
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
    val travel = screenWidth + 420.dp
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DanmakuText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = color.copy(alpha = DanmakuTvDefaults.OPACITY),
            fontSize = DanmakuTvDefaults.FontSize,
            fontWeight = FontWeight.SemiBold,
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.85f),
                blurRadius = 4f,
            ),
        ),
        modifier = modifier,
    )
}

private fun allocateTrack(freeAt: LongArray, now: Long): Int? {
    var best = -1
    var bestAt = Long.MAX_VALUE
    for (i in freeAt.indices) {
        if (freeAt[i] <= now) return i
        if (freeAt[i] < bestAt) {
            bestAt = freeAt[i]
            best = i
        }
    }
    return if (best >= 0 && bestAt - now < 800) best else null
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

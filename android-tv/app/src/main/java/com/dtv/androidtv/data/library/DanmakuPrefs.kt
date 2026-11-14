package com.dtv.androidtv.data.library

/**
 * Persistent danmaku visual prefs — mirrors desktop
 * `frontend/src/features/player/danmuSettings.ts` ranges/defaults.
 */
data class DanmakuStyle(
    /** Font size in sp/px (same numeric scale as desktop). */
    val fontSizePx: Int = DEFAULT_FONT_PX,
    /** 0.2 … 1.0 */
    val opacity: Float = DEFAULT_OPACITY,
    /** Scroll-through duration in ms (larger = slower). */
    val durationMs: Long = DEFAULT_DURATION_MS,
) {
    companion object {
        const val FONT_MIN = 14
        const val FONT_MAX = 30
        const val FONT_STEP = 2
        const val DEFAULT_FONT_PX = 20

        const val OPACITY_MIN = 0.2f
        const val OPACITY_MAX = 1.0f
        const val OPACITY_STEP = 0.05f
        const val DEFAULT_OPACITY = 1.0f

        const val DURATION_MIN = 5_000L
        const val DURATION_MAX = 20_000L
        const val DURATION_STEP = 500L
        const val DEFAULT_DURATION_MS = 10_000L

        fun sanitizeFont(value: Int): Int {
            val stepped = ((value + FONT_STEP / 2) / FONT_STEP) * FONT_STEP
            return stepped.coerceIn(FONT_MIN, FONT_MAX)
        }

        fun sanitizeOpacity(value: Float): Float {
            if (!value.isFinite()) return DEFAULT_OPACITY
            return value.coerceIn(OPACITY_MIN, OPACITY_MAX)
        }

        fun sanitizeDuration(value: Long): Long {
            if (value <= 0L) return DEFAULT_DURATION_MS
            val stepped = ((value + DURATION_STEP / 2) / DURATION_STEP) * DURATION_STEP
            return stepped.coerceIn(DURATION_MIN, DURATION_MAX)
        }

        fun normalize(
            fontSizePx: Int = DEFAULT_FONT_PX,
            opacity: Float = DEFAULT_OPACITY,
            durationMs: Long = DEFAULT_DURATION_MS,
        ): DanmakuStyle =
            DanmakuStyle(
                fontSizePx = sanitizeFont(fontSizePx),
                opacity = sanitizeOpacity(opacity),
                durationMs = sanitizeDuration(durationMs),
            )
    }
}

/** Cycle helpers for DPAD OK on settings / chrome rows. */
object DanmakuStyleCycles {
    fun nextFont(current: Int): Int {
        val v = DanmakuStyle.sanitizeFont(current)
        return if (v >= DanmakuStyle.FONT_MAX) DanmakuStyle.FONT_MIN else v + DanmakuStyle.FONT_STEP
    }

    fun nextOpacity(current: Float): Float {
        val v = DanmakuStyle.sanitizeOpacity(current)
        val next = v + DanmakuStyle.OPACITY_STEP
        return if (next > DanmakuStyle.OPACITY_MAX + 1e-4f) {
            DanmakuStyle.OPACITY_MIN
        } else {
            DanmakuStyle.sanitizeOpacity(next)
        }
    }

    fun nextDuration(current: Long): Long {
        val v = DanmakuStyle.sanitizeDuration(current)
        val next = v + DanmakuStyle.DURATION_STEP
        return if (next > DanmakuStyle.DURATION_MAX) DanmakuStyle.DURATION_MIN else next
    }

    fun formatOpacity(opacity: Float): String =
        "${Math.round(DanmakuStyle.sanitizeOpacity(opacity) * 100)}%"

    fun formatDuration(durationMs: Long): String {
        val sec = DanmakuStyle.sanitizeDuration(durationMs) / 1000.0
        return if (sec == sec.toLong().toDouble()) "${sec.toLong()}s" else "${"%.1f".format(sec)}s"
    }

    fun formatSpeedLabel(durationMs: Long): String {
        // Desktop formatDanmuDuration: shorter duration = faster.
        val d = DanmakuStyle.sanitizeDuration(durationMs)
        val label = when {
            d <= 4_500L -> "极快"
            d <= 7_500L -> "很快"
            d <= 10_000L -> "标准"
            d <= 14_000L -> "稍慢"
            else -> "慢速"
        }
        return "$label (${formatDuration(d)})"
    }
}

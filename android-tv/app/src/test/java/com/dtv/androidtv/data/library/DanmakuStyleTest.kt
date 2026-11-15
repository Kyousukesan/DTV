package com.dtv.androidtv.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards desktop-aligned danmaku style ranges / DPAD cycles (LT-20). */
class DanmakuStyleTest {
    @Test
    fun sanitizeFontSnapsToStepAndClamps() {
        assertEquals(20, DanmakuStyle.sanitizeFont(20))
        assertEquals(14, DanmakuStyle.sanitizeFont(10))
        assertEquals(30, DanmakuStyle.sanitizeFont(99))
        assertEquals(16, DanmakuStyle.sanitizeFont(15))
    }

    @Test
    fun sanitizeOpacityClamps() {
        assertEquals(1.0f, DanmakuStyle.sanitizeOpacity(1.5f), 1e-4f)
        assertEquals(0.2f, DanmakuStyle.sanitizeOpacity(0.05f), 1e-4f)
        assertEquals(0.55f, DanmakuStyle.sanitizeOpacity(0.55f), 1e-4f)
    }

    @Test
    fun sanitizeDurationSnapsAndClamps() {
        assertEquals(10_000L, DanmakuStyle.sanitizeDuration(10_000L))
        assertEquals(5_000L, DanmakuStyle.sanitizeDuration(1_000L))
        assertEquals(20_000L, DanmakuStyle.sanitizeDuration(99_000L))
        assertEquals(10_500L, DanmakuStyle.sanitizeDuration(10_400L))
    }

    @Test
    fun cyclesWrapAtMax() {
        assertEquals(14, DanmakuStyleCycles.nextFont(30))
        assertEquals(22, DanmakuStyleCycles.nextFont(20))
        assertEquals(0.2f, DanmakuStyleCycles.nextOpacity(1.0f), 1e-4f)
        assertTrue(DanmakuStyleCycles.nextOpacity(0.95f) > 0.95f)
        assertEquals(5_000L, DanmakuStyleCycles.nextDuration(20_000L))
        assertEquals(10_500L, DanmakuStyleCycles.nextDuration(10_000L))
    }

    @Test
    fun speedLabelMatchesDesktopBuckets() {
        assertTrue(DanmakuStyleCycles.formatSpeedLabel(5_000L).startsWith("很快"))
        assertTrue(DanmakuStyleCycles.formatSpeedLabel(10_000L).startsWith("标准"))
        assertTrue(DanmakuStyleCycles.formatSpeedLabel(18_000L).startsWith("慢速"))
    }
}

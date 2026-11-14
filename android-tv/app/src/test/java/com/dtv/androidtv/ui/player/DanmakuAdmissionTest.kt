package com.dtv.androidtv.ui.player

import com.dtv.androidtv.data.model.DanmakuKind
import com.dtv.androidtv.data.model.DanmakuMessage
import com.dtv.androidtv.data.model.DanmakuMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the LT-17 admission race: ids must not be marked seen unless admitted
 * or permanently skipped (gift/enter). Simulates rapid buffer growth.
 */
class DanmakuAdmissionTest {
    @Test
    fun chatIsAdmittedAndMarkedSeen() {
        val seen = mutableSetOf<String>()
        val tracks = LongArray(16)
        val msgs = listOf(chat("a", "hello"))
        val out = DanmakuAdmission.admit(
            messages = msgs,
            seen = seen,
            activeCount = 0,
            maxActive = 28,
            trackFreeAt = tracks,
            nowMs = 1_000L,
        )
        assertEquals(1, out.size)
        assertEquals("hello", out[0].message.content)
        assertEquals(DanmakuMode.SCROLL, out[0].mode)
        assertTrue("a" in seen)
    }

    @Test
    fun enterAndGiftAreSkippedButMarkedSeen() {
        val seen = mutableSetOf<String>()
        val tracks = LongArray(16)
        val msgs = listOf(
            msg("e1", DanmakuKind.ENTER, "进入直播间"),
            msg("g1", DanmakuKind.GIFT, "送出礼物"),
            chat("c1", "hi"),
        )
        val out = DanmakuAdmission.admit(
            messages = msgs,
            seen = seen,
            activeCount = 0,
            maxActive = 28,
            trackFreeAt = tracks,
            nowMs = 1_000L,
        )
        assertEquals(1, out.size)
        assertEquals("c1", out[0].message.id)
        assertTrue(seen.containsAll(listOf("e1", "g1", "c1")))
    }

    @Test
    fun densityCapDoesNotMarkUnseenWhenFull() {
        val seen = mutableSetOf<String>()
        val tracks = LongArray(16)
        val msgs = listOf(chat("c1", "one"), chat("c2", "two"))
        val out = DanmakuAdmission.admit(
            messages = msgs,
            seen = seen,
            activeCount = 28,
            maxActive = 28,
            trackFreeAt = tracks,
            nowMs = 1_000L,
        )
        assertTrue(out.isEmpty())
        assertTrue("full buffer must leave ids unseen for a later pass", seen.isEmpty())

        val later = DanmakuAdmission.admit(
            messages = msgs,
            seen = seen,
            activeCount = 0,
            maxActive = 28,
            trackFreeAt = tracks,
            nowMs = 2_000L,
        )
        assertEquals(2, later.size)
        assertTrue(seen.containsAll(listOf("c1", "c2")))
    }

    @Test
    fun rapidPassesDoNotDropAlreadyQueuedIds() {
        val seen = mutableSetOf<String>()
        val tracks = LongArray(16)
        // First pass admits c1.
        val pass1 = DanmakuAdmission.admit(
            messages = listOf(chat("c1", "a")),
            seen = seen,
            activeCount = 0,
            maxActive = 28,
            trackFreeAt = tracks,
            nowMs = 1_000L,
        )
        assertEquals(1, pass1.size)
        // Second pass grows the ring buffer (StateFlow emit); c1 already seen.
        val pass2 = DanmakuAdmission.admit(
            messages = listOf(chat("c1", "a"), chat("c2", "b"), chat("c3", "c")),
            seen = seen,
            activeCount = 1,
            maxActive = 28,
            trackFreeAt = tracks,
            nowMs = 1_050L,
        )
        assertEquals(2, pass2.size)
        assertEquals(listOf("c2", "c3"), pass2.map { it.message.id })
    }

    @Test
    fun allocateTrackReturnsNullWhenAllBusy() {
        val tracks = LongArray(4) { 10_000L }
        assertEquals(null, DanmakuAdmission.allocateTrack(tracks, now = 1_000L))
        tracks[2] = 1_100L // within 800ms grace
        assertEquals(2, DanmakuAdmission.allocateTrack(tracks, now = 1_000L))
    }

    private fun chat(id: String, text: String) =
        msg(id, DanmakuKind.CHAT, text, DanmakuMode.SCROLL)

    private fun msg(
        id: String,
        kind: DanmakuKind,
        text: String,
        mode: DanmakuMode = DanmakuMode.SCROLL,
    ) = DanmakuMessage(
        id = id,
        nickname = "n",
        content = text,
        kind = kind,
        mode = mode,
        at = 0L,
    )
}

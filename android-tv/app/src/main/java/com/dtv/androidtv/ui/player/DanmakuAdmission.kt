package com.dtv.androidtv.ui.player

import com.dtv.androidtv.data.model.DanmakuKind
import com.dtv.androidtv.data.model.DanmakuMessage
import com.dtv.androidtv.data.model.DanmakuMode

/**
 * Pure admission logic for the TV danmaku overlay.
 *
 * Important: ids must only enter [seen] after a bullet is successfully enqueued
 * (or permanently skipped as gift/enter). Marking seen before enqueue drops
 * chat forever when the collector coroutine is cancelled mid-batch — which
 * happened with `LaunchedEffect(messages)` restarting on every StateFlow emit.
 */
object DanmakuAdmission {
    data class Candidate(
        val message: DanmakuMessage,
        val mode: DanmakuMode,
        val track: Int,
        val durationMs: Long,
    )

    /**
     * Decide which fresh messages become on-screen bullets.
     *
     * @param messages current ring buffer (newest at the end)
     * @param seen ids already handled (mutated in place)
     * @param activeCount bullets currently on screen
     * @param maxActive density cap
     * @param trackFreeAt scroll-lane free-at timestamps (mutated for SCROLL)
     * @param nowMs admission clock
     */
    fun admit(
        messages: List<DanmakuMessage>,
        seen: MutableSet<String>,
        activeCount: Int,
        maxActive: Int,
        trackFreeAt: LongArray,
        nowMs: Long,
        scrollDurationMs: Long = DanmakuTvDefaults.SCROLL_DURATION_MS,
        fixedDurationMs: Long = DanmakuTvDefaults.FIXED_DURATION_MS,
    ): List<Candidate> {
        if (messages.isEmpty()) return emptyList()
        val out = ArrayList<Candidate>(8)
        var active = activeCount
        for (msg in messages) {
            if (msg.id in seen) continue
            // Match desktop DanmuOverlay: video layer is chat-only (+ rare SYSTEM).
            when (msg.kind) {
                DanmakuKind.GIFT, DanmakuKind.ENTER -> {
                    seen.add(msg.id)
                    continue
                }
                DanmakuKind.CHAT, DanmakuKind.SYSTEM -> Unit
            }
            if (active >= maxActive) {
                // Leave unseen so a later pass can admit when a lane frees up.
                continue
            }
            val mode = when (msg.kind) {
                DanmakuKind.SYSTEM -> DanmakuMode.TOP
                else -> msg.mode
            }
            val track = if (mode == DanmakuMode.SCROLL) {
                allocateTrack(trackFreeAt, nowMs) ?: continue
            } else {
                0
            }
            val duration = if (mode == DanmakuMode.SCROLL) scrollDurationMs else fixedDurationMs
            if (mode == DanmakuMode.SCROLL) {
                trackFreeAt[track] = nowMs + (duration * 0.35).toLong()
            }
            seen.add(msg.id)
            active += 1
            out += Candidate(
                message = msg,
                mode = mode,
                track = track,
                durationMs = duration,
            )
        }
        return out
    }

    /** First free lane, or a nearly-free lane within 800ms; else null. */
    fun allocateTrack(freeAt: LongArray, now: Long): Int? {
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
}

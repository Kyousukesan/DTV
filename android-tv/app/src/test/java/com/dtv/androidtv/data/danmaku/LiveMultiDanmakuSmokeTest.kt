package com.dtv.androidtv.data.danmaku

import com.dtv.androidtv.data.bilibili.BilibiliClient
import com.dtv.androidtv.data.bilibili.BilibiliDanmakuManager
import com.dtv.androidtv.data.douyin.DouyinClient
import com.dtv.androidtv.data.douyin.DouyinDanmakuManager
import com.dtv.androidtv.data.douyu.DouyuDanmakuManager
import com.dtv.androidtv.data.huya.HuyaClient
import com.dtv.androidtv.data.huya.HuyaDanmakuManager
import com.dtv.androidtv.data.model.DanmakuKind
import com.dtv.androidtv.data.model.LivePlatform
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Optional live danmaku smoke for Douyu / Huya / Douyin / Bilibili.
 *
 * Enable: `-Ddtv.liveMultiDanmakuSmoke=true`
 * Optional rooms (comma-separated platform=room):
 * `-Ddtv.liveMultiDanmakuRooms=douyu:8682569,huya:…,douyin:…,bilibili:…`
 *
 * When a room is omitted, the test tries to pick one from that platform's live list.
 */
class LiveMultiDanmakuSmokeTest {
    @Test
    fun connectsAllConfiguredPlatforms() = runBlocking {
        assumeTrue(
            "set -Ddtv.liveMultiDanmakuSmoke=true to run multi danmaku smoke",
            System.getProperty("dtv.liveMultiDanmakuSmoke") == "true",
        )
        val rooms = resolveRooms()
        println("multi-danmaku smoke rooms=$rooms")
        val results = linkedMapOf<String, String>()
        for ((platform, room) in rooms) {
            if (room.isBlank()) {
                results[platform] = "SKIP (no room)"
                continue
            }
            results[platform] = smokeOne(platform, room)
        }
        results.forEach { (p, r) -> println("  $p → $r") }
        val hardFail = results.any { (_, r) -> r.startsWith("FAIL") }
        assertTrue("at least one platform hard-failed: $results", !hardFail)
    }

    private suspend fun smokeOne(platform: String, room: String): String {
        if (platform == "douyin") {
            com.dtv.androidtv.data.douyin.DouyinWsSign.resetForTests()
        }
        val manager: DanmakuSession = when (LivePlatform.fromId(platform)) {
            LivePlatform.DOUYU -> DouyuDanmakuManager()
            LivePlatform.HUYA -> HuyaDanmakuManager(client = HuyaClient())
            LivePlatform.DOUYIN -> DouyinDanmakuManager(client = DouyinClient())
            LivePlatform.BILIBILI -> BilibiliDanmakuManager(client = BilibiliClient())
        }
        val chats = AtomicInteger(0)
        val any = AtomicInteger(0)
        return try {
            coroutineScope {
                val collect = launch {
                    manager.events.collect { ev ->
                        any.incrementAndGet()
                        if (ev.message.kind == DanmakuKind.CHAT) chats.incrementAndGet()
                    }
                }
                manager.start(room)
                withTimeoutOrNull(28_000L) {
                    while (chats.get() < 1) delay(250)
                }
                delay(400)
                collect.cancel()
            }
            val summary = "ok chats=${chats.get()} any=${any.get()} room=$room"
            if (any.get() < 1) "SOFT $summary (no events in window)" else "OK $summary"
        } catch (e: Exception) {
            "FAIL room=$room ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            manager.cancelAll()
        }
    }

    private fun resolveRooms(): Map<String, String> {
        val out = linkedMapOf(
            "douyu" to (System.getProperty("dtv.liveDanmakuRoom") ?: "8682569"),
            "huya" to "",
            "douyin" to "",
            "bilibili" to "",
        )
        val raw = System.getProperty("dtv.liveMultiDanmakuRooms").orEmpty()
        for (part in raw.split(',')) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val idx = trimmed.indexOf(':')
            if (idx <= 0) continue
            out[trimmed.substring(0, idx).trim().lowercase()] = trimmed.substring(idx + 1).trim()
        }
        if (out["huya"].isNullOrBlank()) {
            out["huya"] = runCatching {
                HuyaClient().fetchLiveList(
                    com.dtv.androidtv.data.huya.HuyaConstants.DEFAULT_GID,
                    1,
                ).items.firstOrNull { it.isLive }?.id.orEmpty()
            }.getOrDefault("")
        }
        if (out["bilibili"].isNullOrBlank()) {
            out["bilibili"] = runCatching {
                BilibiliClient().fetchLiveList(
                    com.dtv.androidtv.data.bilibili.BilibiliConstants.DEFAULT_AREA_ID,
                    1,
                ).items.firstOrNull { it.isLive }?.id.orEmpty()
            }.getOrDefault("")
        }
        if (out["douyin"].isNullOrBlank()) {
            out["douyin"] = runCatching {
                DouyinClient().fetchLiveList(
                    com.dtv.androidtv.data.douyin.DouyinConstants.DEFAULT_PARTITION,
                    1,
                ).items.firstOrNull { it.isLive }?.id.orEmpty()
            }.getOrDefault("")
        }
        return out
    }
}

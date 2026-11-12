package com.dtv.androidtv.data.douyu

import com.dtv.androidtv.data.model.DanmakuKind
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
 * Optional live TCP smoke against Douyu danmaku servers.
 *
 * Enable: `-Ddtv.liveDanmakuSmoke=true`
 * Optional room: `-Ddtv.liveDanmakuRoom=8682569`
 */
class LiveDanmakuSmokeTest {
    @Test
    fun receivesChatWithinWindow() = runBlocking {
        assumeTrue(
            "set -Ddtv.liveDanmakuSmoke=true to run live danmaku smoke",
            System.getProperty("dtv.liveDanmakuSmoke") == "true",
        )
        val room = System.getProperty("dtv.liveDanmakuRoom") ?: "8682569"
        val manager = DouyuDanmakuManager()
        val chats = AtomicInteger(0)
        val any = AtomicInteger(0)
        try {
            coroutineScope {
                val collect = launch {
                    manager.events.collect { ev ->
                        any.incrementAndGet()
                        if (ev.message.kind == DanmakuKind.CHAT) chats.incrementAndGet()
                    }
                }
                manager.start(room)
                withTimeoutOrNull(25_000L) {
                    while (chats.get() < 1 && any.get() < 1) {
                        delay(250)
                    }
                }
                delay(300)
                println("danmaku smoke room=$room chats=${chats.get()} any=${any.get()}")
                collect.cancel()
            }
            assertTrue(
                "expected at least one danmaku event (chat or enter) from room $room",
                any.get() >= 1,
            )
        } finally {
            manager.cancelAll()
        }
    }
}

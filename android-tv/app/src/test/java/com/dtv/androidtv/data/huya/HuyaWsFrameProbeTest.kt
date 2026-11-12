package com.dtv.androidtv.data.huya

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HuyaWsFrameProbeTest {
    @Test
    fun countsFrames() {
        assumeTrue(System.getProperty("dtv.huyaWsProbe") == "true")
        val reg = HuyaDanmakuProtocol.buildRegPayload(17_363_578L)
        val frames = AtomicInteger(0)
        val chat1400 = AtomicInteger(0)
        val done = CountDownLatch(1)

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .proxy(java.net.Proxy.NO_PROXY)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("open code=${response.code}")
                println("open headers:\n${response.headers}")
                val ok = webSocket.send(reg.toByteString())
                println("reg sent=$ok size=${reg.size}")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val n = frames.incrementAndGet()
                val data = bytes.toByteArray()
                val chat = HuyaDanmakuProtocol.decodeChat(data)
                if (chat != null) {
                    chat1400.incrementAndGet()
                    if (chat1400.get() <= 3) println("CHAT ${chat.nickname}: ${chat.content}")
                }
                if (n <= 10) println("frame#$n len=${data.size} hex=${data.take(16).joinToString("") { "%02x".format(it) }}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                println("TEXT len=${text.length}")
                frames.incrementAndGet()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("failure: $t")
                t.printStackTrace(System.out)
                done.countDown()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                println("closing $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("closed $code $reason")
                done.countDown()
            }
        }

        // Attempt to avoid permessage-deflate by not offering it: OkHttp always offers it.
        // Use a raw Request; we'll also try an Interceptor stripping extensions from response? 
        val req = Request.Builder()
            .url(HuyaDanmakuProtocol.WS_URL)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val ws = client.newWebSocket(req, listener)
        // Heartbeat every 5s
        val hb = Thread {
            try {
                var i = 0
                while (i < 3) {
                    Thread.sleep(5000)
                    val ok = ws.send(HuyaDanmakuProtocol.HEARTBEAT_BYTES.toByteString())
                    println("hb sent=$ok")
                    i++
                }
            } catch (_: InterruptedException) {}
        }
        hb.isDaemon = true
        hb.start()
        Thread.sleep(16_000L)
        println("closing from test frames=${frames.get()} chats=${chat1400.get()}")
        ws.close(1000, "bye")
        done.await(3, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdown()
        println("SUMMARY frames=${frames.get()} chats=${chat1400.get()}")
        org.junit.Assert.assertTrue("expected chat frames", chat1400.get() > 0)
    }
}

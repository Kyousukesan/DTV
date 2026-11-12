package com.dtv.androidtv.player

import com.dtv.androidtv.data.douyu.DouyuClient
import com.dtv.androidtv.data.douyu.DouyuConstants
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Live network smoke: Douyu FLV through [DouyuFlvRelayServer] for > one CDN TTL.
 *
 * Enable with: `-Ddtv.liveFlvSmoke=true`
 * Optional shorter run: `-Ddtv.liveFlvSmokeSeconds=90` (still useful; full cycle needs ≥320).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LiveFlvContinuitySmokeTest {
    @Test
    fun continuousPlayPastExpireCycle() {
        assumeTrue(
            "set -Ddtv.liveFlvSmoke=true to run live continuity smoke",
            System.getProperty("dtv.liveFlvSmoke") == "true",
        )
        val targetSeconds = (System.getProperty("dtv.liveFlvSmokeSeconds") ?: "320").toLong()
        val targetMs = targetSeconds * 1_000L

        val client = DouyuClient()
        val page = client.fetchLiveList(DouyuConstants.DEFAULT_CATE2_ID, page = 1, limit = 20)
        assertTrue("need listed rooms", page.items.isNotEmpty())

        var roomId = ""
        var quality = ""
        var line = ""
        var nick = ""
        var format = "flv"
        for (candidate in page.items) {
            try {
                val info = client.streamInfo(candidate.id, "")
                if (!info.isLive || info.variants.isEmpty()) continue
                val variant = info.variants.firstOrNull { it.id != "0" } ?: info.variants.first()
                // Probe that StreamURL works (some listed rooms are not playable).
                client.streamUrl(candidate.id, variant.id, info.lines.firstOrNull()?.id.orEmpty())
                roomId = info.roomId
                quality = variant.id
                line = info.lines.firstOrNull()?.id.orEmpty()
                nick = info.nickname
                format = variant.format
                break
            } catch (_: Exception) {
                continue
            }
        }
        assertTrue("need a Douyu room with playable FLV", roomId.isNotBlank())

        val douyu = DouyuClient()
        val server = DouyuFlvRelayServer { rid, q, l ->
            douyu.streamUrl(rid, q, l)
        }
        val prepared = server.prepare(roomId, quality, line)
        val http = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(20, TimeUnit.SECONDS)
            .build()

        val bytes = AtomicLong(0)
        val started = System.currentTimeMillis()
        val log = StringBuilder()
        log.appendLine(
            "room=$roomId nick=$nick quality=$quality line=$line " +
                "format=$format local=${prepared.localUrl} targetSec=$targetSeconds",
        )

        val req = Request.Builder().url(prepared.localUrl).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                log.appendLine("relay HTTP ${resp.code}: $body")
                File("build/reports/live-flv-continuity.log").apply {
                    parentFile?.mkdirs()
                    writeText(log.toString())
                }
            }
            assertTrue("relay HTTP ${resp.code}", resp.isSuccessful)
            val body = resp.body!!.byteStream()
            val buf = ByteArray(64 * 1024)
            var lastLogAt = 0L
            while (System.currentTimeMillis() - started < targetMs) {
                val n = body.read(buf)
                if (n < 0) {
                    log.appendLine("upstream EOF at t=${(System.currentTimeMillis() - started) / 1000}s")
                    break
                }
                if (n > 0) bytes.addAndGet(n.toLong())
                val elapsed = System.currentTimeMillis() - started
                if (elapsed - lastLogAt >= 30_000L) {
                    lastLogAt = elapsed
                    val stats = server.lastRelayStats()
                    log.appendLine(
                        "t=${elapsed / 1000}s bytes=${bytes.get()} " +
                            "switches=${stats?.segmentSwitches?.get()} event=${stats?.lastEvent}",
                    )
                    println(log.lines().last())
                }
            }
        }

        val elapsed = System.currentTimeMillis() - started
        val stats = server.lastRelayStats()
        log.appendLine(
            "DONE elapsedMs=$elapsed bytes=${bytes.get()} switches=${stats?.segmentSwitches?.get()} " +
                "failures=${stats?.renewFailures?.get()} event=${stats?.lastEvent}",
        )
        val out = File("build/reports/live-flv-continuity.log")
        out.parentFile?.mkdirs()
        out.writeText(log.toString())
        println(log.toString())

        server.shutdown()

        assertTrue("should run near target ${targetMs}ms, got ${elapsed}ms", elapsed >= targetMs * 9 / 10)
        assertTrue("should receive substantial FLV bytes", bytes.get() > 500_000L)
        if (targetMs >= 300_000L) {
            val switches = stats?.segmentSwitches?.get() ?: 0
            // Prefer a real splice; ttl_rotate guarantees ≥1 around the 300s mark.
            assertTrue(
                "expected ≥1 relay splice/rotate past CDN window, got $switches (see ${out.absolutePath})",
                switches >= 1,
            )
        }
    }
}

package com.dtv.androidtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FlvRelayTest {
    @Test
    fun demuxerParsesHeaderAndTags() {
        val payload = flvFileHeader() +
            makeFlvTag(FLV_TAG_VIDEO, 30678210, byteArrayOf(0x17, 0x01, 0xaa.toByte())) +
            makeFlvTag(FLV_TAG_AUDIO, 30678211, byteArrayOf(0xaf.toByte(), 0x01, 0xbb.toByte()))
        val d = FlvByteDemuxer()
        d.push(payload)
        assertTrue(d.skipHeader())
        val video = d.nextTag()!!
        assertEquals(FLV_TAG_VIDEO, video.typ)
        assertEquals(30678210L, video.timestamp)
        assertTrue(video.keyframe)
        assertFalse(video.sequenceHeader)
        val audio = d.nextTag()!!
        assertEquals(FLV_TAG_AUDIO, audio.typ)
        assertEquals(30678211L, audio.timestamp)
        assertEquals(null, d.nextTag())
    }

    @Test
    fun demuxerHandlesSplitChunks() {
        val payload = flvFileHeader() +
            makeFlvTag(FLV_TAG_VIDEO, 1000, byteArrayOf(0x17, 0x01, 0x00, 0x01))
        val d = FlvByteDemuxer()
        val tags = mutableListOf<FlvTag>()
        for (b in payload) {
            d.push(byteArrayOf(b))
            while (true) {
                val tag = d.nextTag() ?: break
                tags += tag
            }
        }
        assertEquals(1, tags.size)
        assertEquals(1000L, tags[0].timestamp)
    }

    @Test
    fun timelineDropsReplayedTags() {
        val timeline = FlvTimeline()
        val first = FlvTag(ByteArray(0), FLV_TAG_VIDEO, 500, 500, keyframe = true, sequenceHeader = false)
        assertTrue(timeline.isAhead(first))
        timeline.observe(first)
        val replay = first.copy(timestamp = 400, pts = 400)
        assertFalse(timeline.isAhead(replay))
        val ahead = first.copy(timestamp = 600, pts = 600)
        assertTrue(timeline.isAhead(ahead))
    }

    /**
     * Accelerated continuity: two short-lived upstreams (EOF after bytes) with
     * tiny TTL prove splice switches ≥1 and keeps outputting past the first cut.
     */
    @Test
    fun relaySplicesAcrossShortSegments() {
        val header = flvFileHeader()
        fun segment(tsBase: Long, keyframe: Boolean): ByteArray {
            val frameType = if (keyframe) 0x17.toByte() else 0x27.toByte()
            return header +
                makeFlvTag(FLV_TAG_VIDEO, tsBase, byteArrayOf(frameType, 0x01, 0x00)) +
                makeFlvTag(FLV_TAG_AUDIO, tsBase + 1, byteArrayOf(0xaf.toByte(), 0x01, 0x00)) +
                makeFlvTag(FLV_TAG_VIDEO, tsBase + 40, byteArrayOf(0x27, 0x01, 0x00))
        }

        val opens = AtomicInteger(0)
        val opener = UpstreamOpener {
            val n = opens.incrementAndGet()
            // Second segment starts later on the timeline with a keyframe.
            val bytes = if (n == 1) segment(1000, keyframe = true) else segment(2000, keyframe = true)
            ByteArrayInputStream(bytes)
        }

        val first = opener.open()
        val out = ByteArrayOutputStream()
        val cancel = AtomicBoolean(false)
        val stats = FlvRelayStats()
        val switches = AtomicInteger(0)

        val thread = Thread {
            relayFlv(
                first = first,
                open = opener,
                output = out,
                cancel = cancel,
                renew = true,
                streamTtlMs = 50L,
                renewLeadMs = 10L,
                stats = stats,
                onSwitch = { _, err -> if (err == null) switches.incrementAndGet() },
            )
        }
        thread.start()
        // Allow a few splice cycles then stop.
        Thread.sleep(400)
        cancel.set(true)
        thread.join(2_000)

        assertTrue("expected splice switch(es), got ${switches.get()} stats=${stats.lastEvent}", switches.get() >= 1)
        assertTrue("expected continuous bytes after splice", out.size() > header.size)
        assertTrue(stats.segmentSwitches.get() >= 1)
    }

    /**
     * Upstream never EOFs but TTL elapses → [relayFlv] must ttl_rotate onto a
     * freshly opened segment (mirrors Douyu edges that keep TCP open past 300s).
     */
    @Test
    fun relayRotatesOnTtlWithoutUpstreamEof() {
        fun endlessPipe(tsBase: Long): InputStream {
            val pin = PipedInputStream(64 * 1024)
            val pout = PipedOutputStream(pin)
            Thread {
                try {
                    pout.write(flvFileHeader())
                    var ts = tsBase
                    while (!Thread.currentThread().isInterrupted) {
                        pout.write(
                            makeFlvTag(
                                FLV_TAG_VIDEO,
                                ts,
                                byteArrayOf(if (ts == tsBase) 0x17 else 0x27, 0x01, 0x00),
                            ),
                        )
                        pout.write(makeFlvTag(FLV_TAG_AUDIO, ts + 1, byteArrayOf(0xaf.toByte(), 0x01)))
                        pout.flush()
                        ts += 40
                        Thread.sleep(10)
                    }
                } catch (_: Exception) {
                    // consumer closed
                } finally {
                    runCatching { pout.close() }
                }
            }.apply { isDaemon = true; start() }
            return pin
        }

        val opens = AtomicInteger(0)
        val opener = UpstreamOpener {
            val n = opens.incrementAndGet()
            endlessPipe(if (n == 1) 1_000 else 50_000)
        }
        val out = ByteArrayOutputStream()
        val cancel = AtomicBoolean(false)
        val stats = FlvRelayStats()
        val thread = Thread {
            relayFlv(
                first = opener.open(),
                open = opener,
                output = out,
                cancel = cancel,
                renew = true,
                streamTtlMs = 80L,
                renewLeadMs = 20L,
                stats = stats,
            )
        }
        thread.start()
        Thread.sleep(350)
        cancel.set(true)
        thread.join(3_000)
        assertTrue(
            "expected ttl_rotate without EOF, event=${stats.lastEvent} switches=${stats.segmentSwitches.get()}",
            stats.segmentSwitches.get() >= 1,
        )
        assertTrue(stats.lastEvent.contains("ttl_rotate") || stats.lastEvent.contains("eof_renew"))
        assertTrue(out.size() > 0)
        assertTrue("opener should re-sign for rotate", opens.get() >= 2)
    }

    @Test
    fun relayPipedProducerSurvivesEofWithPrefetch() {
        // Producer writes FLV for ~120ms then EOF; opener rebuilds a later-timeline segment.
        fun livePipe(tsBase: Long): InputStream {
            val pin = PipedInputStream(64 * 1024)
            val pout = PipedOutputStream(pin)
            Thread {
                try {
                    pout.write(flvFileHeader())
                    var ts = tsBase
                    repeat(8) {
                        pout.write(
                            makeFlvTag(
                                FLV_TAG_VIDEO,
                                ts,
                                byteArrayOf(if (it == 0) 0x17 else 0x27, 0x01, 0x00),
                            ),
                        )
                        pout.write(makeFlvTag(FLV_TAG_AUDIO, ts + 1, byteArrayOf(0xaf.toByte(), 0x01)))
                        pout.flush()
                        ts += 40
                        Thread.sleep(15)
                    }
                } finally {
                    runCatching { pout.close() }
                }
            }.apply { isDaemon = true; start() }
            return pin
        }

        val opens = AtomicInteger(0)
        val opener = UpstreamOpener {
            val n = opens.incrementAndGet()
            livePipe(if (n == 1) 10_000 else 20_000)
        }
        val out = ByteArrayOutputStream()
        val cancel = AtomicBoolean(false)
        val stats = FlvRelayStats()
        val thread = Thread {
            relayFlv(
                first = opener.open(),
                open = opener,
                output = out,
                cancel = cancel,
                renew = true,
                streamTtlMs = 80L,
                renewLeadMs = 20L,
                stats = stats,
            )
        }
        thread.start()
        Thread.sleep(500)
        cancel.set(true)
        thread.join(3_000)
        assertTrue(stats.segmentSwitches.get() >= 1)
        assertTrue(out.size() > 0)
    }

    companion object {
        fun flvFileHeader(): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1, 5))
            out.write(intBytes(9))
            out.write(intBytes(0))
            return out.toByteArray()
        }

        fun makeFlvTag(tagType: Int, timestamp: Long, data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tagType and 0x1f)
            val size = data.size
            out.write((size shr 16) and 0xff)
            out.write((size shr 8) and 0xff)
            out.write(size and 0xff)
            out.write(((timestamp shr 16) and 0xff).toInt())
            out.write(((timestamp shr 8) and 0xff).toInt())
            out.write((timestamp and 0xff).toInt())
            out.write(((timestamp shr 24) and 0xff).toInt())
            out.write(byteArrayOf(0, 0, 0))
            out.write(data)
            out.write(intBytes(11 + size))
            return out.toByteArray()
        }

        private fun intBytes(v: Int): ByteArray =
            byteArrayOf(
                ((v ushr 24) and 0xff).toByte(),
                ((v ushr 16) and 0xff).toByte(),
                ((v ushr 8) and 0xff).toByte(),
                (v and 0xff).toByte(),
            )
    }
}

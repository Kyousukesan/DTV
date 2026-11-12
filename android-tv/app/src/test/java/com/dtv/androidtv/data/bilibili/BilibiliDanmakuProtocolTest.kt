package com.dtv.androidtv.data.bilibili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

class BilibiliDanmakuProtocolTest {
    @Test
    fun encodePacket_headerIsBigEndian() {
        val body = """{"uid":0}""".toByteArray(Charsets.UTF_8)
        val packet = BilibiliDanmakuProtocol.encodePacket(
            BilibiliDanmakuProtocol.OP_AUTH,
            body,
        )
        assertEquals(16 + body.size, packet.size)
        val packLen = ((packet[0].toInt() and 0xff) shl 24) or
            ((packet[1].toInt() and 0xff) shl 16) or
            ((packet[2].toInt() and 0xff) shl 8) or
            (packet[3].toInt() and 0xff)
        assertEquals(packet.size, packLen)
        val op = ((packet[8].toInt() and 0xff) shl 24) or
            ((packet[9].toInt() and 0xff) shl 16) or
            ((packet[10].toInt() and 0xff) shl 8) or
            (packet[11].toInt() and 0xff)
        assertEquals(BilibiliDanmakuProtocol.OP_AUTH, op)
    }

    @Test
    fun decodePackets_inflatesNestedZlib() {
        val innerBody = """{"cmd":"DANMU_MSG"}""".toByteArray(Charsets.UTF_8)
        val inner = BilibiliDanmakuProtocol.encodePacket(
            BilibiliDanmakuProtocol.OP_NOTIFICATION,
            innerBody,
        )
        val zlib = deflate(inner)
        // Build outer ver=2 frame manually.
        val outer = ByteArray(16 + zlib.size)
        val total = outer.size
        outer[0] = (total ushr 24).toByte()
        outer[1] = (total ushr 16).toByte()
        outer[2] = (total ushr 8).toByte()
        outer[3] = total.toByte()
        outer[4] = 0
        outer[5] = 16
        outer[6] = 0
        outer[7] = 2 // ver=zlib
        outer[8] = 0
        outer[9] = 0
        outer[10] = 0
        outer[11] = 5 // op notification (ignored for zlib path)
        outer[12] = 0
        outer[13] = 0
        outer[14] = 0
        outer[15] = 1
        System.arraycopy(zlib, 0, outer, 16, zlib.size)

        val packets = BilibiliDanmakuProtocol.decodePackets(outer)
        assertEquals(1, packets.size)
        assertEquals(BilibiliDanmakuProtocol.OP_NOTIFICATION, packets[0].op)
        assertEquals(String(innerBody, Charsets.UTF_8), String(packets[0].body, Charsets.UTF_8))
    }

    @Test
    fun decodePackets_rejectsBrotli() {
        val body = byteArrayOf(1, 2, 3)
        val frame = ByteArray(16 + body.size)
        val total = frame.size
        frame[0] = (total ushr 24).toByte()
        frame[1] = (total ushr 16).toByte()
        frame[2] = (total ushr 8).toByte()
        frame[3] = total.toByte()
        frame[5] = 16
        frame[7] = 3 // brotli
        frame[11] = 5
        frame[15] = 1
        System.arraycopy(body, 0, frame, 16, body.size)
        try {
            BilibiliDanmakuProtocol.decodePackets(frame)
            throw AssertionError("expected brotli rejection")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("brotli"))
        }
    }

    @Test
    fun colorHex_formatsRgb() {
        assertEquals("", BilibiliDanmakuProtocol.colorHex(0))
        assertEquals("#FF0000", BilibiliDanmakuProtocol.colorHex(0xFF0000))
        assertEquals("#00AABB", BilibiliDanmakuProtocol.colorHex(0x00AABB))
    }

    private fun deflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }
}

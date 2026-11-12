package com.dtv.androidtv.data.douyu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DouyuDanmakuProtocolTest {
    @Test
    fun encodeSttMatchesDeclaredLengthLayout() {
        val msg = "type@=mrkl/"
        val packet = DouyuDanmakuProtocol.encodeStt(msg)
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val declared = buf.int
        assertEquals(declared, buf.int)
        assertEquals(689, buf.short.toInt() and 0xffff)
        assertEquals(0, buf.get().toInt())
        assertEquals(0, buf.get().toInt())
        val body = ByteArray(msg.toByteArray().size)
        buf.get(body)
        assertEquals(msg, String(body, Charsets.UTF_8))
        assertEquals(0, buf.get().toInt())
        assertEquals(msg.toByteArray().size + 9, declared)
        assertEquals(declared + 4, packet.size)
    }

    @Test
    fun readPacketRoundTrip() {
        val encoded = DouyuDanmakuProtocol.encodeStt("type@=loginreq/roomid@=123/")
        val read = DouyuDanmakuProtocol.readPacket(ByteArrayInputStream(encoded))
        assertTrue(read.contentEquals(encoded))
        val fields = DouyuDanmakuProtocol.parseStt(DouyuDanmakuProtocol.packetBody(read))
        assertEquals("loginreq", fields["type"])
        assertEquals("123", fields["roomid"])
    }

    @Test
    fun parseSttUnescapesSlashAndAt() {
        val fields = DouyuDanmakuProtocol.parseStt("type@=chatmsg/txt@=a@Sb@Ac/nn@=bob/")
        assertEquals("chatmsg", fields["type"])
        assertEquals("a/b@c", fields["txt"])
        assertEquals("bob", fields["nn"])
    }

    @Test
    fun colorFromDouyuColUsesPaletteNotBlack() {
        assertEquals("", DouyuDanmakuProtocol.colorFromDouyuCol(""))
        assertEquals("", DouyuDanmakuProtocol.colorFromDouyuCol("0"))
        assertEquals("#FF2E2E", DouyuDanmakuProtocol.colorFromDouyuCol("1"))
        assertEquals("#FF6600", DouyuDanmakuProtocol.colorFromDouyuCol("4"))
        assertEquals("", DouyuDanmakuProtocol.colorFromDouyuCol("99"))
    }
}

package com.dtv.androidtv.data.bilibili

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream

/**
 * Bilibili live danmaku framing — port of Go `internal/platforms/bilibili/danmaku.go`.
 *
 * Packet layout (big-endian):
 * `u32 pack_len | u16 header_len(=16) | u16 ver | u32 op | u32 seq | body`
 *
 * `ver=2` body is zlib of **nested** packets (recurse). `ver=3` (brotli) is rejected;
 * clients advertise `protover=2` so zlib stays available without a brotli dependency.
 */
object BilibiliDanmakuProtocol {
    const val OP_HEARTBEAT: Int = 2
    const val OP_HEARTBEAT_REPLY: Int = 3
    const val OP_NOTIFICATION: Int = 5
    const val OP_AUTH: Int = 7
    const val OP_AUTH_REPLY: Int = 8

    /** Client accepts zlib-compressed frames (not brotli protover=3). */
    const val PROTOVER: Int = 2

    const val HEADER_LEN: Int = 16
    const val MAX_FRAME_SIZE: Int = 8 shl 20
    private const val MAX_NEST_DEPTH: Int = 8

    data class Packet(
        val op: Int,
        val ver: Int,
        val body: ByteArray,
    )

    /** Assemble one 16-byte BE header + [body]. */
    fun encodePacket(op: Int, body: ByteArray): ByteArray {
        val total = HEADER_LEN + body.size
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(total)
        buf.putShort(HEADER_LEN.toShort())
        buf.putShort(1) // ver for outbound frames
        buf.putInt(op)
        buf.putInt(1) // seq
        buf.put(body)
        return buf.array()
    }

    /**
     * Split one WebSocket binary frame into logical packets.
     * zlib (`ver=2`) bodies are inflated and decoded recursively.
     */
    fun decodePackets(data: ByteArray, depth: Int = 0): List<Packet> {
        if (depth > MAX_NEST_DEPTH) {
            throw IllegalStateException("嵌套层级过深")
        }
        val out = mutableListOf<Packet>()
        var offset = 0
        while (offset + HEADER_LEN <= data.size) {
            val packLen = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            var headerLen = ByteBuffer.wrap(data, offset + 4, 2)
                .order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
            val ver = ByteBuffer.wrap(data, offset + 6, 2)
                .order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
            val op = ByteBuffer.wrap(data, offset + 8, 4).order(ByteOrder.BIG_ENDIAN).int

            if (headerLen < HEADER_LEN) headerLen = HEADER_LEN
            if (packLen < headerLen + 1 || offset + packLen > data.size) break

            val body = data.copyOfRange(offset + headerLen, offset + packLen)
            offset += packLen

            when (ver) {
                2 -> {
                    val plain = inflate(body)
                    out += decodePackets(plain, depth + 1)
                }
                3 -> throw IllegalStateException("收到 brotli 帧（ver=3），当前仅支持 zlib")
                else -> out += Packet(op = op, ver = ver, body = body)
            }
        }
        return out
    }

    /** Inflate a zlib-wrapped payload (limit [MAX_FRAME_SIZE]). */
    fun inflate(data: ByteArray): ByteArray {
        InflaterInputStream(ByteArrayInputStream(data)).use { input ->
            val chunks = ArrayList<ByteArray>(4)
            var total = 0
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (total + n > MAX_FRAME_SIZE) {
                    throw IllegalStateException("zlib 解压超过上限")
                }
                chunks += buf.copyOf(n)
                total += n
            }
            val merged = ByteArray(total)
            var off = 0
            for (c in chunks) {
                System.arraycopy(c, 0, merged, off, c.size)
                off += c.size
            }
            return merged
        }
    }

    /** Decimal RGB → `#RRGGBB`; 0 / invalid → "" (UI default). */
    fun colorHex(color: Long): String {
        if (color <= 0L) return ""
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return "#%02X%02X%02X".format(r, g, b)
    }
}

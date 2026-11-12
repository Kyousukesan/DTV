package com.dtv.androidtv.data.douyu

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Douyu STT danmaku framing — port of Go `internal/platforms/douyu/danmaku.go`.
 *
 * Transport on Android is cleartext TCP to `danmuproxy.douyu.com:8601+`
 * (WSS :8506 is dead; same STT body as the old WebSocket era).
 *
 * Packet layout (LE):
 * `u32 len(=msgLen+9) | u32 len | u16 689 | 0x00 | 0x00 | msg | 0x00`
 * Declared length excludes the first 4 bytes (total = declared + 4).
 */
object DouyuDanmakuProtocol {
    const val CLIENT_TYPE: Int = 689
    const val MAX_PACKET_SIZE: Int = 1 shl 16

    /** Official palette for Douyu `col` index (not decimal RGB). */
    private val COL_COLORS = mapOf(
        1 to "#FF2E2E",
        2 to "#00CCFF",
        3 to "#66FF00",
        4 to "#FF6600",
        5 to "#CC00FF",
        6 to "#F6447F",
        7 to "#FF84F6",
    )

    fun encodeStt(msg: String): ByteArray {
        val msgBytes = msg.toByteArray(Charsets.UTF_8)
        val declared = msgBytes.size + 9
        val out = ByteBuffer.allocate(msgBytes.size + 13).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(declared)
        out.putInt(declared)
        out.putShort(CLIENT_TYPE.toShort())
        out.put(0)
        out.put(0)
        out.put(msgBytes)
        out.put(0)
        return out.array()
    }

    /**
     * Reads one full STT packet (header + body). Returns the complete bytes
     * including the leading length field.
     */
    fun readPacket(input: InputStream): ByteArray {
        val header = readFully(input, 4)
        val declared = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        if (declared < 8 || declared > MAX_PACKET_SIZE) {
            throw IllegalStateException("弹幕包长度异常: $declared")
        }
        val rest = readFully(input, declared.toInt())
        return header + rest
    }

    /** Body string after 12-byte header, stripping trailing NUL. */
    fun packetBody(packet: ByteArray): String {
        if (packet.size < 13) return ""
        val end = packet.size - 1 // drop trailing \0
        return String(packet, 12, end - 12, Charsets.UTF_8)
    }

    /** Parse `k1@=v1/k2@=v2/` with `@S` → `/` and `@A` → `@`. */
    fun parseStt(content: String): Map<String, String> {
        val result = LinkedHashMap<String, String>(16)
        for (item in content.split('/')) {
            if (item.isEmpty()) continue
            val sep = item.indexOf("@=")
            if (sep <= 0) continue
            val key = item.substring(0, sep)
            var value = item.substring(sep + 2)
            value = value.replace("@S", "/").replace("@A", "@")
            result[key] = value
        }
        return result
    }

    /**
     * Map Douyu `col` palette index to `#RRGGBB`.
     * Empty / 0 / unknown → "" so UI keeps default white (never black).
     */
    fun colorFromDouyuCol(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val n = trimmed.toIntOrNull() ?: return ""
        if (n <= 0) return ""
        COL_COLORS[n]?.let { return it }
        if (n in 0x10000..0xFFFFFF) {
            return "#%06X".format(n)
        }
        return ""
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(buf, off, length - off)
            if (n < 0) throw EOFException("unexpected EOF reading STT ($off/$length)")
            off += n
        }
        return buf
    }
}

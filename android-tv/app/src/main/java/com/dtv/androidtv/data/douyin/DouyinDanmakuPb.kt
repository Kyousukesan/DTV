package com.dtv.androidtv.data.douyin

/**
 * Hand-written protobuf subset for Douyin danmaku (Go `pb.go`).
 *
 * Only PushFrame / Response / Message / ChatMessage / User fields we need —
 * avoids shipping a full protoc dump of douyin.proto.
 *
 * Field map (from douyin.proto):
 * - PushFrame: logId=2, payloadType=7, payload=8
 * - Response: messagesList=1, internalExt=5, needAck=9
 * - Message: method=1, payload=2
 * - ChatMessage: user=2, content=3
 * - User: nickName=3, payGrade=23, fansClub=24
 * - PayGrade: level=3; FansClub.data=1 → FansClubData.level=2
 */
internal object DouyinDanmakuPb {
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_BYTES = 2
    private const val WIRE_FIXED32 = 5

    data class PbField(
        val num: Int,
        val wire: Int,
        val varint: Long = 0L,
        val bytes: ByteArray = ByteArray(0),
    )

    data class PushFrame(
        val logId: Long = 0L,
        val payloadType: String = "",
        val payload: ByteArray = ByteArray(0),
    ) {
        fun encode(): ByteArray {
            var buf = ByteArray(0)
            buf = appendUint(buf, 2, logId)
            buf = appendString(buf, 7, payloadType)
            buf = appendBytes(buf, 8, payload)
            return buf
        }
    }

    data class ResponseMsg(
        val method: String,
        val payload: ByteArray,
    )

    data class Response(
        val messages: List<ResponseMsg> = emptyList(),
        val internalExt: String = "",
        val needAck: Boolean = false,
    )

    data class ChatMessage(
        val content: String = "",
        val nickname: String = "",
        val userLevel: Long = 0L,
        val fansClubLv: Long = 0L,
    )

    fun decodePushFrame(data: ByteArray): PushFrame {
        val fields = readFields(data)
        return PushFrame(
            logId = varint(fields, 2),
            payloadType = string(fields, 7),
            payload = bytes(fields, 8),
        )
    }

    fun decodeResponse(data: ByteArray): Response {
        val fields = readFields(data)
        val messages = ArrayList<ResponseMsg>()
        for (f in fields) {
            if (f.num != 1 || f.wire != WIRE_BYTES) continue
            val sub = runCatching { readFields(f.bytes) }.getOrNull() ?: continue
            messages += ResponseMsg(
                method = string(sub, 1),
                payload = bytes(sub, 2),
            )
        }
        return Response(
            messages = messages,
            internalExt = string(fields, 5),
            needAck = bool(fields, 9),
        )
    }

    fun decodeChatMessage(data: ByteArray): ChatMessage {
        val fields = readFields(data)
        var out = ChatMessage(content = string(fields, 3))
        val userBytes = bytes(fields, 2)
        if (userBytes.isEmpty()) return out
        val userFields = runCatching { readFields(userBytes) }.getOrNull() ?: return out
        out = out.copy(nickname = string(userFields, 3))

        val pg = bytes(userFields, 23)
        if (pg.isNotEmpty()) {
            runCatching { readFields(pg) }.getOrNull()?.let { pgFields ->
                out = out.copy(userLevel = varint(pgFields, 3))
            }
        }
        val fc = bytes(userFields, 24)
        if (fc.isNotEmpty()) {
            runCatching { readFields(fc) }.getOrNull()?.let { fcFields ->
                val d = bytes(fcFields, 1)
                if (d.isNotEmpty()) {
                    runCatching { readFields(d) }.getOrNull()?.let { dFields ->
                        out = out.copy(fansClubLv = varint(dFields, 2))
                    }
                }
            }
        }
        return out
    }

    fun readFields(data: ByteArray): List<PbField> {
        val out = ArrayList<PbField>()
        var i = 0
        while (i < data.size) {
            val (key, n) = readUvarint(data, i)
            if (n <= 0) throw IllegalArgumentException("protobuf: key parse failed at $i")
            i += n
            val num = (key ushr 3).toInt()
            val wire = (key and 0x7L).toInt()
            if (num <= 0) throw IllegalArgumentException("protobuf: bad field number $num")
            when (wire) {
                WIRE_VARINT -> {
                    val (v, m) = readUvarint(data, i)
                    if (m <= 0) throw IllegalArgumentException("protobuf: varint failed field $num")
                    i += m
                    out += PbField(num = num, wire = wire, varint = v)
                }
                WIRE_BYTES -> {
                    val (len, m) = readUvarint(data, i)
                    if (m <= 0) throw IllegalArgumentException("protobuf: length failed field $num")
                    i += m
                    val end = i + len.toInt()
                    if (end > data.size) {
                        throw IllegalArgumentException(
                            "protobuf: field $num overrun need $len have ${data.size - i}",
                        )
                    }
                    out += PbField(num = num, wire = wire, bytes = data.copyOfRange(i, end))
                    i = end
                }
                WIRE_FIXED64 -> i += 8
                WIRE_FIXED32 -> i += 4
                else -> throw IllegalArgumentException("protobuf: unsupported wire $wire field $num")
            }
        }
        return out
    }

    fun bytes(fields: List<PbField>, num: Int): ByteArray {
        for (f in fields) {
            if (f.num == num && f.wire == WIRE_BYTES) return f.bytes
        }
        return ByteArray(0)
    }

    fun string(fields: List<PbField>, num: Int): String =
        String(bytes(fields, num), Charsets.UTF_8)

    fun varint(fields: List<PbField>, num: Int): Long {
        for (f in fields) {
            if (f.num == num && f.wire == WIRE_VARINT) return f.varint
        }
        return 0L
    }

    fun bool(fields: List<PbField>, num: Int): Boolean = varint(fields, num) != 0L

    fun appendVarint(buf: ByteArray, v: Long): ByteArray {
        var x = v
        val tmp = ArrayList<Byte>(10)
        while (x >= 0x80L) {
            tmp += ((x and 0x7FL) or 0x80L).toByte()
            x = x ushr 7
        }
        tmp += x.toByte()
        return buf + tmp.toByteArray()
    }

    fun appendTag(buf: ByteArray, num: Int, wire: Int): ByteArray =
        appendVarint(buf, (num.toLong() shl 3) or wire.toLong())

    fun appendString(buf: ByteArray, num: Int, s: String): ByteArray {
        if (s.isEmpty()) return buf
        val raw = s.toByteArray(Charsets.UTF_8)
        var out = appendTag(buf, num, WIRE_BYTES)
        out = appendVarint(out, raw.size.toLong())
        return out + raw
    }

    fun appendBytes(buf: ByteArray, num: Int, b: ByteArray): ByteArray {
        if (b.isEmpty()) return buf
        var out = appendTag(buf, num, WIRE_BYTES)
        out = appendVarint(out, b.size.toLong())
        return out + b
    }

    fun appendUint(buf: ByteArray, num: Int, v: Long): ByteArray {
        if (v == 0L) return buf
        var out = appendTag(buf, num, WIRE_VARINT)
        return appendVarint(out, v)
    }

    /** Decode unsigned varint; returns (value, bytesConsumed). bytesConsumed <= 0 on failure. */
    fun readUvarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < data.size && shift < 64) {
            val b = data[i].toInt() and 0xFF
            i++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to (i - offset)
            shift += 7
        }
        return 0L to -1
    }
}

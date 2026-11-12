package com.dtv.androidtv.data.huya

/**
 * Minimal TARS/JCE codec subset for Huya WUP (port of Go `tars.go`).
 *
 * Type ids match tars-stream 0.1.1 (not the common JCE docs where 10=BYTE_ARRAY):
 * 0 int8, 1 int16, 2 int32, 3 int64, 4 float, 5 double,
 * 6 string1, 7 string4, 8 map, 9 list, 10 struct beg, 11 struct end, 12 zero, 13 simple list.
 */
internal object HuyaTars {
    const val T_INT8: Int = 0
    const val T_INT16: Int = 1
    const val T_INT32: Int = 2
    const val T_INT64: Int = 3
    const val T_FLOAT: Int = 4
    const val T_DOUBLE: Int = 5
    const val T_STRING1: Int = 6
    const val T_STRING4: Int = 7
    const val T_MAPS: Int = 8
    const val T_LIST: Int = 9
    const val T_STRUCT_BEG: Int = 10
    const val T_STRUCT_END: Int = 11
    const val T_ZERO: Int = 12
    const val T_SIMPLE: Int = 13
}

internal class TarsWriter {
    private val buf = ArrayList<Byte>(256)

    fun bytes(): ByteArray = buf.toByteArray()

    private fun putHead(tag: Int, typ: Int) {
        val t = tag and 0xFF
        if (t < 15) {
            buf += ((t shl 4) or typ).toByte()
        } else {
            buf += (0xF0 or typ).toByte()
            buf += t.toByte()
        }
    }

    fun writeInt8(tag: Int, v: Byte) {
        if (v.toInt() == 0) {
            putHead(tag, HuyaTars.T_ZERO)
            return
        }
        putHead(tag, HuyaTars.T_INT8)
        buf += v
    }

    fun writeInt16(tag: Int, v: Short) {
        if (v in -128..127) {
            writeInt8(tag, v.toByte())
            return
        }
        putHead(tag, HuyaTars.T_INT16)
        buf += (v.toInt() shr 8).toByte()
        buf += v.toByte()
    }

    fun writeInt32(tag: Int, v: Int) {
        if (v in -32768..32767) {
            writeInt16(tag, v.toShort())
            return
        }
        putHead(tag, HuyaTars.T_INT32)
        buf += (v shr 24).toByte()
        buf += (v shr 16).toByte()
        buf += (v shr 8).toByte()
        buf += v.toByte()
    }

    fun writeInt64(tag: Int, v: Long) {
        if (v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            writeInt32(tag, v.toInt())
            return
        }
        putHead(tag, HuyaTars.T_INT64)
        for (shift in 56 downTo 0 step 8) {
            buf += (v shr shift).toByte()
        }
    }

    fun writeString(tag: Int, s: String) {
        val data = s.toByteArray(Charsets.UTF_8)
        if (data.size <= 255) {
            putHead(tag, HuyaTars.T_STRING1)
            buf += data.size.toByte()
            data.forEach { buf += it }
            return
        }
        putHead(tag, HuyaTars.T_STRING4)
        val n = data.size
        buf += (n shr 24).toByte()
        buf += (n shr 16).toByte()
        buf += (n shr 8).toByte()
        buf += n.toByte()
        data.forEach { buf += it }
    }

    fun writeBytes(tag: Int, data: ByteArray) {
        putHead(tag, HuyaTars.T_SIMPLE)
        putHead(0, HuyaTars.T_INT8)
        writeInt32(0, data.size)
        data.forEach { buf += it }
    }

    fun writeEmptyMap(tag: Int) {
        putHead(tag, HuyaTars.T_MAPS)
        writeInt32(0, 0)
    }

    fun writeBytesMap(tag: Int, key: String, value: ByteArray) {
        putHead(tag, HuyaTars.T_MAPS)
        writeInt32(0, 1)
        writeString(0, key)
        writeBytes(1, value)
    }

    fun writeStructBegin(tag: Int) {
        putHead(tag, HuyaTars.T_STRUCT_BEG)
    }

    fun writeStructEnd() {
        putHead(0, HuyaTars.T_STRUCT_END)
    }
}

internal data class TarsHead(val tag: Int, val typ: Int)

internal class TarsReader(private val buf: ByteArray) {
    var pos: Int = 0
        private set

    /** Last integer value — used when skipping simple-list byte arrays. */
    var lastSize: Int = 0
        private set

    fun takeHead(): TarsHead {
        if (pos >= buf.size) error("tars: 数据不足")
        val b = buf[pos].toInt() and 0xFF
        pos++
        var tag = (b and 0xF0) shr 4
        val typ = b and 0x0F
        if (tag == 15) {
            if (pos >= buf.size) error("tars: 扩展 tag 数据不足")
            tag = buf[pos].toInt() and 0xFF
            pos++
        }
        return TarsHead(tag, typ)
    }

    /** Scan forward for [tag]; rewind to start if missing (crate TarsTagNotFoundErr). */
    fun find(tag: Int): TarsHead {
        val start = pos
        while (pos < buf.size) {
            val head = try {
                takeHead()
            } catch (_: Exception) {
                break
            }
            if (head.tag == tag && head.typ != HuyaTars.T_STRUCT_END) {
                return head
            }
            try {
                skipField(head.typ)
            } catch (_: Exception) {
                break
            }
        }
        pos = start
        error("tars: 找不到 tag=$tag")
    }

    fun skipField(typ: Int) {
        fun advance(n: Int) {
            if (pos + n > buf.size) error("tars: 跳过失败")
            pos += n
        }
        when (typ) {
            HuyaTars.T_INT8 -> advance(1)
            HuyaTars.T_INT16 -> advance(2)
            HuyaTars.T_INT32, HuyaTars.T_FLOAT -> advance(4)
            HuyaTars.T_INT64, HuyaTars.T_DOUBLE -> advance(8)
            HuyaTars.T_ZERO, HuyaTars.T_STRUCT_END -> Unit
            HuyaTars.T_STRING1 -> {
                if (pos >= buf.size) error("tars: 数据不足")
                val size = buf[pos].toInt() and 0xFF
                pos++
                advance(size)
            }
            HuyaTars.T_STRING4 -> {
                if (pos + 4 > buf.size) error("tars: 数据不足")
                val size = ((buf[pos].toInt() and 0xFF) shl 24) or
                    ((buf[pos + 1].toInt() and 0xFF) shl 16) or
                    ((buf[pos + 2].toInt() and 0xFF) shl 8) or
                    (buf[pos + 3].toInt() and 0xFF)
                pos += 4
                advance(size)
            }
            HuyaTars.T_SIMPLE -> {
                takeHead()
                readInt32(0, 0)
                advance(lastSize)
            }
            HuyaTars.T_MAPS -> {
                val count = readInt32(0, 0)
                repeat(count * 2) {
                    val head = takeHead()
                    skipField(head.typ)
                }
            }
            HuyaTars.T_LIST -> {
                val count = readInt32(0, 0)
                repeat(count) {
                    val head = takeHead()
                    skipField(head.typ)
                }
            }
            HuyaTars.T_STRUCT_BEG -> {
                while (pos < buf.size) {
                    val head = takeHead()
                    if (head.typ == HuyaTars.T_STRUCT_END) return
                    skipField(head.typ)
                }
                error("tars: 结构体未结束")
            }
            else -> error("tars: 未知类型 $typ")
        }
    }

    fun readInt32Tag(tag: Int, def: Int): Int {
        val head = try {
            find(tag)
        } catch (_: Exception) {
            return def
        }
        return intFromHead(head, def)
    }

    fun readInt32(tag: Int, def: Int): Int {
        val head = try {
            takeHead()
        } catch (_: Exception) {
            return def
        }
        if (head.tag != tag) {
            pos--
            return def
        }
        return intFromHead(head, def)
    }

    private fun intFromHead(head: TarsHead, def: Int): Int {
        val v: Long = when (head.typ) {
            HuyaTars.T_ZERO -> {
                lastSize = 0
                return 0
            }
            HuyaTars.T_INT8 -> {
                if (pos + 1 > buf.size) return def
                val n = buf[pos].toInt()
                pos++
                n.toLong()
            }
            HuyaTars.T_INT16 -> {
                if (pos + 2 > buf.size) return def
                val n = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                pos += 2
                n.toShort().toLong()
            }
            HuyaTars.T_INT32 -> {
                if (pos + 4 > buf.size) return def
                val n = ((buf[pos].toInt() and 0xFF) shl 24) or
                    ((buf[pos + 1].toInt() and 0xFF) shl 16) or
                    ((buf[pos + 2].toInt() and 0xFF) shl 8) or
                    (buf[pos + 3].toInt() and 0xFF)
                pos += 4
                n.toLong()
            }
            HuyaTars.T_INT64 -> {
                if (pos + 8 > buf.size) return def
                var n = 0L
                for (i in 0 until 8) {
                    n = (n shl 8) or (buf[pos + i].toInt() and 0xFF).toLong()
                }
                pos += 8
                n
            }
            else -> return def
        }
        lastSize = v.toInt()
        return v.toInt()
    }

    fun readStringTag(tag: Int, def: String): String {
        val head = try {
            find(tag)
        } catch (_: Exception) {
            return def
        }
        return when (head.typ) {
            HuyaTars.T_ZERO -> def
            HuyaTars.T_STRING1 -> {
                if (pos + 1 > buf.size) return def
                val size = buf[pos].toInt() and 0xFF
                pos++
                if (pos + size > buf.size) return def
                val s = buf.copyOfRange(pos, pos + size).toString(Charsets.UTF_8)
                pos += size
                s
            }
            HuyaTars.T_STRING4 -> {
                if (pos + 4 > buf.size) return def
                val size = ((buf[pos].toInt() and 0xFF) shl 24) or
                    ((buf[pos + 1].toInt() and 0xFF) shl 16) or
                    ((buf[pos + 2].toInt() and 0xFF) shl 8) or
                    (buf[pos + 3].toInt() and 0xFF)
                pos += 4
                if (pos + size > buf.size) return def
                val s = buf.copyOfRange(pos, pos + size).toString(Charsets.UTF_8)
                pos += size
                s
            }
            else -> def
        }
    }

    fun readBytesTag(tag: Int): ByteArray {
        val head = find(tag)
        if (head.typ != HuyaTars.T_SIMPLE) {
            error("tars: tag=$tag 不是字节数组（type=${head.typ}）")
        }
        takeHead()
        val size = readInt32(0, 0)
        if (size < 0 || pos + size > buf.size) {
            error("tars: 字节数组长度异常 $size")
        }
        val out = buf.copyOfRange(pos, pos + size)
        pos += size
        return out
    }

    fun readStructBegin(tag: Int) {
        val head = find(tag)
        if (head.typ != HuyaTars.T_STRUCT_BEG) {
            error("tars: tag=$tag 不是结构体（type=${head.typ}）")
        }
    }
}

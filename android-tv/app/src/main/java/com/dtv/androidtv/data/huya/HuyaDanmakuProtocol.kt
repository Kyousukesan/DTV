package com.dtv.androidtv.data.huya

/**
 * Huya danmaku WS framing — port of Go `internal/platforms/huya/danmaku.go`.
 *
 * Transport: binary WebSocket to [HuyaConstants.URL_DANMAKU_WS].
 * Subscribe: TARS wscmd int32(16) + oos list topics `live:<channelId>` / `chat:<channelId>`.
 * Decode: outer type 7 → nested 1400 chat payload.
 */
object HuyaDanmakuProtocol {
    const val WS_URL: String = HuyaConstants.URL_DANMAKU_WS
    const val OUTER_CHAT_TYPE: Int = 7
    const val NESTED_CHAT: Int = 1400
    const val DEFAULT_COLOR: Int = 16_777_215 // 0xFFFFFF

    /**
     * Captured `onlineui.OnUserHeartBeat` bytes from Go `danmakuHeartbeatBytes`.
     * Do not re-encode — hand-built TARS is easy to get wrong.
     */
    val HEARTBEAT_BYTES: ByteArray = byteArrayOf(
        0x00, 0x03, 0x1d, 0x00, 0x00, 0x69, 0x00, 0x00, 0x00, 0x69, 0x10, 0x03,
        0x2c, 0x3c, 0x4c, 0x56, 0x08, 0x6f, 0x6e, 0x6c, 0x69, 0x6e, 0x65, 0x75,
        0x69, 0x66, 0x0f, 0x4f, 0x6e, 0x55, 0x73, 0x65, 0x72, 0x48, 0x65, 0x61,
        0x72, 0x74, 0x42, 0x65, 0x61, 0x74, 0x7d, 0x00, 0x00, 0x3c, 0x08, 0x00,
        0x01, 0x06, 0x04, 0x74, 0x52, 0x65, 0x71, 0x1d, 0x00, 0x00, 0x2f, 0x0a,
        0x0a, 0x0c, 0x16, 0x00, 0x26, 0x00, 0x36, 0x07, 0x61, 0x64, 0x72, 0x5f,
        0x77, 0x61, 0x70, 0x46, 0x00, 0x0b, 0x12, 0x03, 0xae.toByte(), 0xf0.toByte(), 0x0f, 0x22,
        0x03, 0xae.toByte(), 0xf0.toByte(), 0x0f, 0x3c, 0x42, 0x6d, 0x52, 0x02, 0x60, 0x5c, 0x60,
        0x01, 0x7c, 0x82.toByte(), 0x00, 0x0b, 0xb0.toByte(), 0x1f, 0x9c.toByte(), 0xac.toByte(), 0x0b, 0x8c.toByte(), 0x98.toByte(),
        0x0c, 0xa8.toByte(), 0x0c,
    )

    /**
     * Build WS register payload for [channelId] (must be channel ID, not yyid).
     *
     * Structure (Go `get_ws_info_tars`):
     * - oos = list of string (tag0) topics + string(tag1) empty
     * - wscmd = int32(tag0)=16 + bytes(tag1)=oos
     */
    fun buildRegPayload(channelId: Long): ByteArray {
        val uid = channelId.toString()
        val topics = listOf("live:$uid", "chat:$uid")
        val oos = TarsWriter()
        oos.writeStringList(0, topics)
        oos.writeString(1, "")
        val wscmd = TarsWriter()
        wscmd.writeInt32(0, 16)
        wscmd.writeBytes(1, oos.bytes())
        return wscmd.bytes()
    }

    data class ChatPayload(
        val nickname: String,
        val content: String,
        val color: String,
    )

    /**
     * Decode one WS binary frame into a chat payload, or null if not chat / empty.
     *
     * Outer: int32(tag0)=7, bytes(tag1)
     * Inner: int32(tag1)=1400, bytes(tag2)
     * Payload: struct(tag0)=HuyaUser, string(tag3)=content, struct(tag6)=color
     */
    fun decodeChat(data: ByteArray): ChatPayload? {
        val outer = TarsReader(data)
        val outerType = outer.readInt32Tag(0, -1)
        if (outerType != OUTER_CHAT_TYPE) {
            debugLog("outer type=$outerType (not 7, ignore)")
            return null
        }
        val b1 = try {
            outer.readBytesTag(1)
        } catch (e: Exception) {
            debugLog("read outer bytes failed: ${e.message}")
            return null
        }

        val inner = TarsReader(b1)
        val nested = inner.readInt32Tag(1, -1)
        val b2 = try {
            inner.readBytesTag(2)
        } catch (e: Exception) {
            debugLog("read inner bytes failed (nested=$nested): ${e.message}")
            return null
        }
        if (nested != NESTED_CHAT) {
            debugLog("non-chat nested=$nested, skip")
            return null
        }

        val payload = TarsReader(b2)
        var nickname = ""
        try {
            payload.readStructBegin(0)
            // HuyaUser: tag0=uid, tag1=imid, tag2=nick, tag3=gender.
            // tag3 MUST be consumed or it steals content's tag3.
            payload.readInt64Tag(0, -1)
            payload.readInt64Tag(1, -1)
            nickname = payload.readStringTag(2, "")
            payload.readInt32Tag(3, -1)
        } catch (_: Exception) {
            // User struct optional / malformed — still try content.
        }
        val text = payload.readStringTag(3, "")
        var color = DEFAULT_COLOR
        try {
            payload.readStructBegin(6)
            color = payload.readInt32Tag(0, DEFAULT_COLOR)
        } catch (_: Exception) {
            // color optional
        }

        if (text.trim().isEmpty()) {
            debugLog("empty chat content, skip")
            return null
        }
        return ChatPayload(
            nickname = nickname.ifEmpty { "匿名" },
            content = text,
            color = colorHex(color),
        )
    }

    /** Decimal RGB → `#RRGGBB` (<=0 → white). */
    fun colorHex(color: Int): String {
        var c = color
        if (c <= 0) return "#FFFFFF"
        if (c > 0xFFFFFF) c = c and 0xFFFFFF
        return "#%06X".format(c)
    }

    private fun debugLog(msg: String) {
        if (System.getenv("DTV_DANMAKU_DEBUG").isNullOrEmpty()) return
        println("D/HuyaDanmakuProtocol: $msg")
    }
}

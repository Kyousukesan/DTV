package com.dtv.androidtv.data.huya

import com.dtv.androidtv.data.douyu.JsonUtil
import com.dtv.androidtv.data.model.Category
import com.dtv.androidtv.data.model.CategoryGroup
import com.dtv.androidtv.data.model.LineOption
import com.dtv.androidtv.data.model.LivePlatform
import com.dtv.androidtv.data.model.StreamInfo
import com.dtv.androidtv.data.model.StreamVariant
import com.dtv.androidtv.data.model.Streamer
import com.dtv.androidtv.data.model.StreamerPage
import com.dtv.androidtv.data.net.HttpJsonClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import okio.ByteString.Companion.decodeBase64
import kotlin.math.max
import kotlin.random.Random

/**
 * Kotlin contract-port of Go `internal/platforms/huya.Client`.
 *
 * Scope: static categories, live list, room card, FLV stream resolve (WUP token + anti-code),
 * and danmaku channel-id resolution for WS subscribe.
 * Search intentionally omitted.
 */
class HuyaClient(
    private val http: HttpJsonClient = HttpJsonClient(),
) {
    // ---- Categories (static embed) ----------------------------------------

    /** Parse embed `huya/categories.json` (`FetchCategories`). */
    fun fetchCategories(): List<CategoryGroup> {
        synchronized(categoryLock) {
            categoryCache?.let { return it }
            categoryError?.let { throw it }
            try {
                val raw = loadCategoriesJson()
                val arr = JSONArray(raw)
                val out = mutableListOf<CategoryGroup>()
                for (i in 0 until arr.length()) {
                    val group = arr.optJSONObject(i) ?: continue
                    val subs = group.optJSONArray("subcategories") ?: JSONArray()
                    val cats = mutableListOf<Category>()
                    for (j in 0 until subs.length()) {
                        val sub = subs.optJSONObject(j) ?: continue
                        val id = sub.optString("id").trim()
                        if (id.isEmpty()) continue
                        cats += Category(id = id, name = sub.optString("title"))
                    }
                    if (cats.isEmpty()) continue
                    // Original table has no group id — use 1-based index like Go.
                    out += CategoryGroup(
                        id = (i + 1).toString(),
                        name = group.optString("title"),
                        categories = cats,
                    )
                }
                if (out.isEmpty()) {
                    throw IllegalStateException("虎牙分区表为空")
                }
                categoryCache = out
                return out
            } catch (e: Exception) {
                val err = if (e is IllegalStateException) e else IllegalStateException("解析虎牙分区表失败: ${e.message}", e)
                categoryError = err
                throw err
            }
        }
    }

    // ---- Live list --------------------------------------------------------

    /** Page of rooms for a gid (`FetchLiveList`). Huya ignores page size (~120/page). */
    fun fetchLiveList(
        gid: String,
        page: Int = 1,
        limit: Int = HuyaConstants.DEFAULT_PAGE_SIZE,
    ): StreamerPage {
        val id = normalizeGid(gid)
        if (id.isEmpty()) throw IllegalArgumentException("缺少虎牙分区 id")
        val safePage = max(1, page)
        val safeLimit = if (limit <= 0) HuyaConstants.DEFAULT_PAGE_SIZE else limit
        val target = HuyaConstants.URL_LIVE_LIST.format(id, safePage, safeLimit)
        val payload = http.getJson(
            target,
            mapOf(
                "User-Agent" to HuyaConstants.DESKTOP_UA,
                "Referer" to HuyaConstants.REFERER,
                "Accept" to "*/*",
            ),
        )
        val vList = payload.optJSONArray("vList") ?: JSONArray()
        val rooms = buildList {
            for (i in 0 until vList.length()) {
                mapLiveListItem(vList.optJSONObject(i))?.let { add(it) }
            }
        }
        // Do NOT truncate: Huya returns ~120/page; cutting drops rooms never seen on page+1.
        val totalPages = payload.optInt("iTotalPage", 0)
        val pageNo = JsonUtil.asLong(payload.opt("iPageNo"))
        val reportedSize = JsonUtil.asLong(payload.opt("iPageSize"))
        var hasMore = pageNo < totalPages.toLong()
        if (totalPages == 0) {
            val effective = when {
                reportedSize > 0 -> reportedSize.toInt()
                else -> safeLimit
            }
            hasMore = rooms.size >= effective
        }
        return StreamerPage(
            items = rooms,
            page = safePage,
            hasMore = hasMore,
            totalPages = totalPages,
        )
    }

    private fun mapLiveListItem(raw: JSONObject?): Streamer? {
        if (raw == null) return null
        val roomId = JsonUtil.asString(raw.opt("lProfileRoom"))
        if (roomId.isEmpty()) return null
        var nickname = JsonUtil.asString(raw.opt("sNick"))
        if (nickname.isEmpty()) nickname = "房间 $roomId"
        return Streamer(
            id = roomId,
            platform = LivePlatform.HUYA.id,
            nickname = nickname,
            title = JsonUtil.asString(raw.opt("sIntroduction")),
            cover = JsonUtil.asString(raw.opt("sScreenshot")),
            avatar = JsonUtil.asString(raw.opt("sAvatar180")),
            viewers = JsonUtil.asDouble(raw.opt("lUserCount")).toLong(),
            categoryName = firstNonEmpty(
                JsonUtil.asString(raw.opt("sGameFullName")),
                JsonUtil.asString(raw.opt("sGameHostName")),
            ),
            isLive = true,
        )
    }

    // ---- Room detail / streamer card --------------------------------------

    private data class StreamCandidate(
        val cdnType: String,
        val flvUrl: String,
        val streamName: String,
        val channelId: Long,
        val flvAntiCode: String,
    )

    private data class ProfileData(
        val title: String,
        val nickname: String,
        val avatar: String,
        val bitRateInfo: String,
        val candidates: List<StreamCandidate>,
        val flvRates: List<Int>,
        /** Raw `baseSteamInfoList[0].lChannelId` (pre CDN-sort) — preferred danmaku subscribe uid. */
        val baseStreamChannelId: Long = 0L,
        /** `liveData.channel` fallback. */
        val liveChannel: Long = 0L,
        /** `liveData.uid` fallback. */
        val liveUid: Long = 0L,
        /** `profileInfo.yyid` — last resort before roomId (yyid alone yields almost no chat). */
        val yyid: Long = 0L,
    )

    /** Light room card (`Streamer` / `FetchRoomInfo`). */
    fun fetchRoomInfo(roomId: String): Streamer {
        require(roomId.isNotBlank()) { "缺少房间号" }
        val data = fetchProfile(roomId)
        return Streamer(
            id = roomId,
            platform = LivePlatform.HUYA.id,
            nickname = data.nickname.ifEmpty { "房间 $roomId" },
            title = data.title,
            cover = "",
            avatar = data.avatar,
            viewers = 0L,
            categoryName = "",
            isLive = data.candidates.isNotEmpty(),
        )
    }

    // ---- Stream resolve ---------------------------------------------------

    /**
     * Playback entry: room meta + source/high/standard FLV variants + CDN lines.
     *
     * Huya qualities share one signed base URL; high/standard append `&ratio=N`.
     */
    fun streamInfo(roomId: String, line: String = ""): StreamInfo {
        require(roomId.isNotBlank()) { "缺少房间号" }
        val data = fetchProfile(roomId)
        val lines = data.candidates.map {
            LineOption(id = it.cdnType.lowercase(), label = it.cdnType.uppercase())
        }
        val info = StreamInfo(
            roomId = roomId,
            platform = LivePlatform.HUYA.id,
            title = data.title,
            nickname = data.nickname,
            avatar = data.avatar,
            isLive = data.candidates.isNotEmpty(),
            variants = emptyList(),
            lines = lines,
        )
        if (!info.isLive) return info

        val selected = selectCandidate(data.candidates, line)
        val bitrates = availableBitrates(data)
        val variantsDef = buildVariants(bitrates)
        // Token is quality-agnostic — fetch once, then append ratio per ladder step.
        val token = resolveToken(selected)
        val variants = mutableListOf<StreamVariant>()
        for (variant in variantsDef) {
            try {
                val ratio = if (variant.id == "source") null else variant.bitRate
                val url = assembleStreamUrl(selected, token, ratio)
                variants += StreamVariant(
                    id = variant.id,
                    label = variant.label,
                    url = url,
                    format = "flv",
                    urls = listOf(url),
                )
            } catch (_: Exception) {
                // One quality failing must not drop the others.
            }
        }
        if (variants.isEmpty()) {
            throw IllegalStateException("虎牙取流失败：没有可用清晰度")
        }
        return info.copy(variants = variants)
    }

    /** Single FLV URL for quality/line (`StreamURL`). */
    fun streamUrl(roomId: String, quality: String = "", line: String = ""): String {
        require(roomId.isNotBlank()) { "缺少房间号" }
        val data = fetchProfile(roomId)
        if (data.candidates.isEmpty()) throw IllegalStateException("房间当前未开播")
        val selected = selectCandidate(data.candidates, line)
        val ratio = resolveQuality(quality, availableBitrates(data))
        val token = resolveToken(selected)
        return assembleStreamUrl(selected, token, ratio)
    }

    /**
     * Resolve the WS subscribe uid for danmaku (Go `buildRegPayload`).
     *
     * Prefer **channel ID**, not yyid:
     * `baseSteamInfoList[0].lChannelId` → `liveData.channel` → `liveData.uid` →
     * `profileInfo.yyid` → numeric roomId.
     */
    fun resolveDanmakuChannelId(roomId: String): Long {
        require(roomId.isNotBlank()) { "缺少房间号" }
        val data = fetchProfile(roomId.trim())
        var ayyuid = data.baseStreamChannelId
        if (ayyuid == 0L) {
            ayyuid = data.candidates.firstOrNull()?.channelId ?: 0L
        }
        if (ayyuid == 0L) ayyuid = data.liveChannel
        if (ayyuid == 0L) ayyuid = data.liveUid
        if (ayyuid == 0L) ayyuid = data.yyid
        if (ayyuid == 0L) ayyuid = roomId.trim().toLongOrNull() ?: 0L
        if (ayyuid == 0L) {
            throw IllegalStateException("无法确定虎牙频道 ID（房间 $roomId）")
        }
        return ayyuid
    }

    // ---- profileRoom ------------------------------------------------------

    private fun fetchProfile(roomId: String): ProfileData {
        val target = HuyaConstants.URL_PROFILE_ROOM.format(
            URLEncoder.encode(roomId, Charsets.UTF_8.name()),
        )
        val payload = http.getJson(
            target,
            mapOf(
                "User-Agent" to HuyaConstants.FIREFOX_UA,
                "Referer" to HuyaConstants.REFERER,
                "Origin" to "https://www.huya.com",
                "Accept" to "*/*",
            ),
        )
        val status = payload.optInt("status")
        if (status != 200) {
            throw IllegalStateException("虎牙房间档案返回 status=$status（房间可能不存在）")
        }
        val data = payload.optJSONObject("data") ?: JSONObject()
        val liveData = data.optJSONObject("liveData") ?: JSONObject()
        val profileInfo = data.optJSONObject("profileInfo") ?: JSONObject()
        val stream = data.optJSONObject("stream")

        val nick = firstNonEmpty(
            profileInfo.optString("nick"),
            liveData.optString("nick"),
        )
        val title = firstNonEmpty(
            liveData.optString("introduction"),
            liveData.optString("roomName"),
        )
        val avatar = firstNonEmpty(
            profileInfo.optString("avatar180"),
            liveData.optString("avatar180"),
        )
        val bitRateInfo = liveData.optString("bitRateInfo")
        val liveChannel = JsonUtil.asLong(liveData.opt("channel"))
        val liveUid = JsonUtil.asLong(liveData.opt("uid"))
        val yyid = JsonUtil.asLong(profileInfo.opt("yyid"))
        val flvRates = mutableListOf<Int>()
        stream?.optJSONObject("flv")?.optJSONArray("rateArray")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val br = JsonUtil.asLong(item.opt("iBitRate")).toInt()
                if (br > 0) flvRates += br
            }
        }
        val (candidates, baseChannelId) = parseCandidates(stream)
        return ProfileData(
            title = title,
            nickname = nick.ifEmpty { "房间 $roomId" },
            avatar = avatar,
            bitRateInfo = bitRateInfo,
            candidates = candidates,
            flvRates = flvRates,
            baseStreamChannelId = baseChannelId,
            liveChannel = liveChannel,
            liveUid = liveUid,
            yyid = yyid,
        )
    }

    /** Returns CDN-sorted candidates plus raw first-entry channel id for danmaku. */
    private fun parseCandidates(stream: JSONObject?): Pair<List<StreamCandidate>, Long> {
        if (stream == null) return emptyList<StreamCandidate>() to 0L
        // Typo "baseSteamInfoList" is Huya's own spelling.
        val list = stream.optJSONArray("baseSteamInfoList") ?: JSONArray()
        var fallbackUid = 0L
        if (list.length() > 0) {
            fallbackUid = JsonUtil.asLong(list.optJSONObject(0)?.opt("lChannelId"))
        }
        val out = mutableListOf<StreamCandidate>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val cdn = item.optString("sCdnType").trim()
            val flvUrl = item.optString("sFlvUrl").trim()
            val streamName = item.optString("sStreamName").trim()
            if (cdn.isEmpty() || flvUrl.isEmpty() || streamName.isEmpty()) continue
            var channelId = JsonUtil.asLong(item.opt("lChannelId"))
            if (channelId == 0L && fallbackUid != 0L) channelId = fallbackUid
            if (channelId == 0L) continue
            out += StreamCandidate(
                cdnType = cdn,
                flvUrl = flvUrl,
                streamName = streamName,
                channelId = channelId,
                flvAntiCode = item.optString("sFlvAntiCode"),
            )
        }
        return out.sortedBy { cdnPriority(it.cdnType) } to fallbackUid
    }

    // ---- WUP token + anti-code --------------------------------------------

    private fun resolveToken(candidate: StreamCandidate): String {
        return try {
            getCdnToken(candidate.flvUrl, candidate.streamName)
        } catch (e: Exception) {
            // Fallback: profileRoom sometimes already ships a usable anti-code query.
            val fallback = candidate.flvAntiCode.trim()
            if (fallback.isNotEmpty()) fallback else throw e
        }
    }

    /**
     * WUP `liveui.getCdnTokenInfoEx` — TARS RequestPacket over HTTP.
     * Response UniAttribute holds `tRsp.sFlvToken` used by [buildAntiCode].
     */
    private fun getCdnToken(flvUrl: String, streamName: String): String {
        val req = TarsWriter()
        req.writeStructBegin(0)
        req.writeString(0, flvUrl)
        req.writeString(1, streamName)
        req.writeInt32(2, HuyaConstants.WUP_LOOP_TIME)
        req.writeStructBegin(3) // HuyaUserId
        req.writeInt64(0, 0)
        req.writeString(1, "")
        req.writeString(2, "")
        req.writeString(3, HuyaConstants.WUP_HYUA)
        req.writeString(4, "")
        req.writeInt32(5, 0)
        req.writeString(6, "")
        req.writeString(7, "")
        req.writeStructEnd()
        req.writeInt32(4, HuyaConstants.WUP_APP_ID)
        req.writeStructEnd()

        val tup = TarsWriter()
        tup.writeBytesMap(0, "tReq", req.bytes())
        val packet = encodeWupRequest(1, HuyaConstants.WUP_SERVANT, HuyaConstants.WUP_FUNC, tup.bytes())

        val body = http.postBytes(
            HuyaConstants.URL_WUP,
            packet,
            mapOf(
                "User-Agent" to HuyaConstants.HYSDK_UA,
                "Origin" to HuyaConstants.WUP_ORIGIN,
                "Referer" to HuyaConstants.WUP_REFERER,
                "Accept" to "*/*",
                "Content-Type" to "application/octet-stream",
            ),
        )
        val (ret, payload, desc) = decodeWupResponse(body)
        if (ret != 0) {
            val msg = if (desc.isEmpty()) "虎牙 WUP 返回 ret=$ret" else "虎牙 WUP 返回 ret=$ret: $desc"
            throw IllegalStateException(msg)
        }
        val token = decodeTokenFromTup(payload)
        if (token.isEmpty()) throw IllegalStateException("虎牙 WUP 没有返回可用 token")
        return token
    }

    private fun encodeWupRequest(requestId: Int, servant: String, fn: String, tupPayload: ByteArray): ByteArray {
        val w = TarsWriter()
        w.writeInt16(1, 3) // iVersion
        w.writeInt8(2, 0) // cPacketType
        w.writeInt32(3, 0) // iMessageType
        w.writeInt32(4, requestId)
        w.writeString(5, servant)
        w.writeString(6, fn)
        w.writeBytes(7, tupPayload)
        w.writeInt32(8, 0) // iTimeout
        w.writeEmptyMap(9)
        w.writeEmptyMap(10)
        val body = w.bytes()
        val total = body.size + 4
        val out = ByteArray(total)
        out[0] = (total shr 24).toByte()
        out[1] = (total shr 16).toByte()
        out[2] = (total shr 8).toByte()
        out[3] = total.toByte()
        System.arraycopy(body, 0, out, 4, body.size)
        return out
    }

    /**
     * Strip 4-byte length prefix and read ResponsePacket.
     * Must consume tags in ascending order — peeking tag 1 first misaligns later finds.
     */
    private fun decodeWupResponse(buf: ByteArray): Triple<Int, ByteArray, String> {
        var payload = buf
        if (buf.size >= 4) {
            val declared = ((buf[0].toInt() and 0xFF) shl 24) or
                ((buf[1].toInt() and 0xFF) shl 16) or
                ((buf[2].toInt() and 0xFF) shl 8) or
                (buf[3].toInt() and 0xFF)
            if (declared in 4..buf.size) {
                payload = buf.copyOfRange(4, declared)
            }
        }
        val r = TarsReader(payload)
        val ret = r.readInt32Tag(5, -1)
        try {
            val sBuffer = r.readBytesTag(6)
            val desc = r.readStringTag(8, "")
            return Triple(ret, sBuffer, desc)
        } catch (_: Exception) {
            // Some WUP replies use request-shaped layout with sBuffer at tag 7.
            val r2 = TarsReader(payload)
            return try {
                Triple(0, r2.readBytesTag(7), "")
            } catch (_: Exception) {
                Triple(-1, ByteArray(0), "")
            }
        }
    }

    private fun decodeTokenFromTup(payload: ByteArray): String {
        if (payload.isEmpty()) throw IllegalStateException("虎牙 WUP 返回空 payload")
        decodeStringBytesMap(payload)?.let { m ->
            m["tRsp"]?.let { decodeTokenStruct(it) }?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val outer = decodeStringBytesMap(payload)
            ?: throw IllegalStateException("无法解析虎牙 WUP 响应")
        val inner = outer["tRsp"] ?: throw IllegalStateException("虎牙 WUP 响应缺少 tRsp")
        decodeStringBytesMap(inner)?.let { m ->
            m["GetCdnTokenExResp"]?.let { decodeTokenStruct(it) }?.takeIf { it.isNotEmpty() }?.let { return it }
            for (v in m.values) {
                decodeTokenStruct(v).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return decodeTokenStruct(inner)
    }

    private fun decodeStringBytesMap(payload: ByteArray): Map<String, ByteArray>? {
        val r = TarsReader(payload)
        val head = try {
            r.takeHead()
        } catch (_: Exception) {
            return null
        }
        if (head.typ != HuyaTars.T_MAPS) return null
        val count = r.readInt32(0, 0)
        if (count !in 0..64) return null
        val out = LinkedHashMap<String, ByteArray>(count)
        repeat(count) {
            val key = r.readStringTag(0, "")
            val value = try {
                r.readBytesTag(1)
            } catch (_: Exception) {
                return null
            }
            out[key] = value
        }
        return out
    }

    private fun decodeTokenStruct(payload: ByteArray): String {
        return try {
            val r = TarsReader(payload)
            r.readStructBegin(0)
            r.readStringTag(0, "").trim()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Rebuild play auth query from WUP token (pure_live / Go `buildAntiCode`):
     * seqid = channelId + nowMs; secretHash = md5(seqid|ctype|platform);
     * wsSecret = md5(fmPrefix_calcUid_streamName_secretHash_wsTime).
     */
    private fun buildAntiCode(streamName: String, presenterUid: Long, token: String): String {
        val sanitized = token.replace("&amp;", "&")
        val trimmed = sanitized.trimStart('?', '&')
        val params = parseQuery(trimmed)
        val fm = params["fm"].orEmpty()
        if (fm.isEmpty()) return trimmed

        val ctype = params["ctype"].orEmpty().ifEmpty { "huya_pc_exe" }
        val platformId = params["t"].orEmpty().toLongOrNull() ?: 0L
        val isWap = platformId == 103L

        val calcStart = System.currentTimeMillis()
        val seqId = presenterUid + calcStart
        val secretHash = md5Hex("$seqId|$ctype|$platformId")

        val convertUid = rotl32By8(presenterUid)
        val calcUid = if (isWap) presenterUid else convertUid

        val fmDecoded = try {
            URLDecoder.decode(fm, Charsets.UTF_8.name())
        } catch (_: Exception) {
            fm
        }
        val fmPlain = base64StdDecode(fmDecoded)
        val secretPrefix = fmPlain.toString(Charsets.UTF_8).substringBefore('_')
        if (secretPrefix.isEmpty()) {
            throw IllegalStateException("无法从 fm 推导 wsSecret 前缀")
        }
        val wsTime = params["wsTime"].orEmpty()
        if (wsTime.isEmpty()) throw IllegalStateException("anti-code 缺少 wsTime")
        val fs = params["fs"].orEmpty()
        if (fs.isEmpty()) throw IllegalStateException("anti-code 缺少 fs")

        val secretStr = "${secretPrefix}_${calcUid}_${streamName}_${secretHash}_$wsTime"
        val wsSecret = md5Hex(secretStr)

        val wsTimeInt = wsTime.trim().toLongOrNull(16) ?: 0L
        val ct = ((wsTimeInt + Random.nextDouble()) * 1000).toLong()
        var uuid = (((ct % 10_000_000_000L) + Random.nextDouble()) * 1000).toLong()
        uuid %= 0xFFFFFFFFL

        val parts = mutableListOf<String>()
        fun add(k: String, v: String) {
            parts += "$k=$v"
        }
        add("wsSecret", wsSecret)
        add("wsTime", wsTime)
        add("seqid", seqId.toString())
        add("ctype", ctype)
        add("ver", "1")
        add("fs", fs)
        add("fm", URLEncoder.encode(fm, Charsets.UTF_8.name()))
        add("t", platformId.toString())
        if (isWap) {
            add("uid", presenterUid.toString())
            add("uuid", uuid.toString())
        } else {
            add("u", convertUid.toString())
        }
        return parts.joinToString("&")
    }

    private fun assembleStreamUrl(candidate: StreamCandidate, token: String, ratio: Int?): String {
        val anti = buildAntiCode(candidate.streamName, candidate.channelId, token)
        var base = candidate.flvUrl.trimEnd('/') + "/" + candidate.streamName + ".flv?" + anti + "&codec=264"
        base = enforceHttps(base)
        return if (ratio != null) "$base&ratio=$ratio" else base
    }

    // ---- quality / CDN helpers --------------------------------------------

    private data class RateVariant(
        val id: String,
        val bitRate: Int,
        val label: String,
    )

    private fun availableBitrates(data: ProfileData): List<Int> {
        val out = mutableListOf<Int>()
        if (data.bitRateInfo.isNotBlank()) {
            try {
                val arr = JSONArray(data.bitRateInfo)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val br = JsonUtil.asLong(item.opt("iBitRate")).toInt()
                    if (br > 0) out += br
                }
            } catch (_: Exception) {
                // fall through to flv.rateArray
            }
        }
        if (out.isEmpty()) out += data.flvRates
        return out.sorted().distinct()
    }

    private fun buildVariants(bitrates: List<Int>): List<RateVariant> {
        var hd = 4000
        var sd = 2000
        if (bitrates.isNotEmpty()) {
            hd = bitrates.last()
            sd = bitrates.first()
        }
        return listOf(
            RateVariant(id = "source", bitRate = 0, label = "原画"),
            RateVariant(id = "high", bitRate = hd, label = "高清 ${hd}K"),
            RateVariant(id = "standard", bitRate = sd, label = "标清 ${sd}K"),
        )
    }

    private fun resolveQuality(quality: String, bitrates: List<Int>): Int? {
        val q = quality.trim()
        val lower = q.lowercase()
        var hd = 4000
        var sd = 2000
        if (bitrates.isNotEmpty()) {
            hd = bitrates.last()
            sd = bitrates.first()
        }
        return when {
            q.isEmpty() || lower == "source" || q.contains("原画") -> null
            lower == "high" || q.contains("高清") || lower == "hd" -> hd
            lower == "standard" || q.contains("标清") || lower == "sd" || lower == "ld" -> sd
            else -> q.toIntOrNull()?.takeIf { it > 0 } ?: hd
        }
    }

    private fun selectCandidate(candidates: List<StreamCandidate>, line: String): StreamCandidate {
        val target = line.trim().lowercase()
        if (target.isNotEmpty()) {
            candidates.firstOrNull { it.cdnType.equals(target, ignoreCase = true) }?.let { return it }
        }
        return candidates.first()
    }

    companion object {
        private val categoryLock = Any()

        @Volatile
        private var categoryCache: List<CategoryGroup>? = null

        @Volatile
        private var categoryError: Exception? = null

        fun normalizeGid(raw: String): String {
            var text = raw.trim()
            val gIdx = text.lastIndexOf("/g/")
            if (gIdx >= 0) text = text.substring(gIdx + 3)
            val cut = text.indexOfAny(charArrayOf('?', '#'))
            if (cut >= 0) text = text.substring(0, cut)
            return text.trim('/').removePrefix("g_")
        }

        fun firstNonEmpty(vararg values: String): String =
            values.firstOrNull { it.isNotBlank() }.orEmpty()

        fun cdnPriority(cdn: String): Int =
            when (cdn.lowercase()) {
                "tx" -> 0
                "al" -> 1
                "hs" -> 2
                else -> 3
            }

        fun enforceHttps(raw: String): String =
            when {
                raw.startsWith("https://") -> raw
                raw.startsWith("http://") -> "https://" + raw.removePrefix("http://")
                else -> raw
            }

        fun md5Hex(value: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** Rotate low 32 bits left by 8 (Go `rotl32By8`). */
        fun rotl32By8(value: Long): Long {
            val low = (value and 0xFFFFFFFFL).toUInt()
            val rotated = (low shl 8) or (low shr 24)
            val high = value and 0xFFFFFFFFL.inv()
            return high or rotated.toLong()
        }

        fun base64StdDecode(s: String): ByteArray {
            var std = s.replace('-', '+').replace('_', '/')
            val m = std.length % 4
            if (m != 0) std += "=".repeat(4 - m)
            return std.decodeBase64()?.toByteArray()
                ?: throw IllegalArgumentException("fm base64 解码失败")
        }

        fun parseQuery(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            return raw.split('&').mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val k = part.substring(0, idx)
                val v = part.substring(idx + 1)
                k to v
            }.toMap()
        }

        fun loadCategoriesJson(): String {
            val stream = HuyaClient::class.java.classLoader
                ?.getResourceAsStream(HuyaConstants.CATEGORIES_RESOURCE)
                ?: throw IllegalStateException(
                    "缺少虎牙分区表资源 ${HuyaConstants.CATEGORIES_RESOURCE}",
                )
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}

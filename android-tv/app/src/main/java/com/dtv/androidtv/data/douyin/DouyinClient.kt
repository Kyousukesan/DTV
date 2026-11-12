package com.dtv.androidtv.data.douyin

import android.util.Log
import com.dtv.androidtv.data.douyu.JsonUtil
import com.dtv.androidtv.data.model.Category
import com.dtv.androidtv.data.model.CategoryGroup
import com.dtv.androidtv.data.model.LivePlatform
import com.dtv.androidtv.data.model.StreamInfo
import com.dtv.androidtv.data.model.StreamVariant
import com.dtv.androidtv.data.model.Streamer
import com.dtv.androidtv.data.model.StreamerPage
import com.dtv.androidtv.data.net.HttpJsonClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Kotlin contract-port of Go `internal/platforms/douyin.Client`.
 *
 * Browse → play only (categories, live list, room meta, stream resolve).
 * Danmaku / login intentionally omitted.
 *
 * Cookie note: Go keeps a shared jar that picks up `ttwid` from the live home
 * page. OkHttp here has no jar by default, so we parse `Set-Cookie` ourselves
 * and attach a `Cookie` header on later calls. Warmup is best-effort and must
 * not block the first list request (list works without ttwid; warm mainly
 * primes TLS + cookie for reflow / future WS).
 */
class DouyinClient(
    private val http: HttpJsonClient = HttpJsonClient(),
) {
    private val cookieLock = Any()
    private val cookies = linkedMapOf<String, String>()
    private var warmedAtMs: Long = 0L
    private var warmErr: Exception? = null
    private val categoriesLock = Any()
    private var categoriesCache: List<CategoryGroup>? = null

    init {
        // Non-blocking warm — same rationale as Go NewClient().
        Thread({
            try {
                warmup()
            } catch (_: Exception) {
                // warmErr already recorded inside warmup
            }
        }, "douyin-cookie-warm").apply { isDaemon = true }.start()
    }

    // ---- Cookie warm ------------------------------------------------------

    /**
     * Hit live home once so Douyin can Set-Cookie `ttwid` (and friends).
     * Cached for [DouyinConstants.WARM_TTL_MS].
     */
    fun warmup(): Unit {
        synchronized(cookieLock) {
            val now = System.currentTimeMillis()
            if (warmedAtMs > 0 && now - warmedAtMs < DouyinConstants.WARM_TTL_MS) {
                warmErr?.let { throw it }
                return
            }
            try {
                http.get(
                    DouyinConstants.URL_LIVE_HOME,
                    mapOf(
                        "User-Agent" to DouyinConstants.DESKTOP_UA,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "zh-CN,zh;q=0.9",
                    ),
                ).use { resp ->
                    mergeSetCookies(resp.headers("Set-Cookie"))
                    // Drain body so OkHttp can keep the connection (Go comment).
                    resp.body?.bytes()
                }
                warmedAtMs = now
                warmErr = null
            } catch (e: Exception) {
                warmedAtMs = 0L
                warmErr = IOException("抖音首页 cookie 获取失败: ${e.message}", e)
                throw warmErr!!
            }
        }
    }

    private fun mergeSetCookies(setCookieHeaders: List<String>) {
        synchronized(cookieLock) {
            for (header in setCookieHeaders) {
                val part = header.substringBefore(';')
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val name = part.substring(0, eq).trim()
                val value = part.substring(eq + 1).trim()
                if (name.isNotEmpty()) cookies[name] = value
            }
        }
    }

    private fun cookieHeader(): String =
        synchronized(cookieLock) {
            if (cookies.isEmpty()) "" else cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

    // ---- Categories (static embed) ----------------------------------------

    /**
     * Static partition tree from classpath `douyin/categories.json`
     * (Go `//go:embed assets/categories.json`). Ids are `partition_type_partition`.
     */
    fun fetchCategories(): List<CategoryGroup> {
        synchronized(categoriesLock) {
            categoriesCache?.let { return it }
            val stream = javaClass.classLoader?.getResourceAsStream("douyin/categories.json")
                ?: error("missing classpath resource douyin/categories.json")
            val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(raw)
            val groups = buildList {
                for (i in 0 until arr.length()) {
                    val g = arr.optJSONObject(i) ?: continue
                    val gid = JsonUtil.asString(g.opt("id"))
                    if (gid.isEmpty()) continue
                    val catsArr = g.optJSONArray("categories") ?: JSONArray()
                    val cats = buildList {
                        for (j in 0 until catsArr.length()) {
                            val c = catsArr.optJSONObject(j) ?: continue
                            val cid = JsonUtil.asString(c.opt("id"))
                            if (cid.isEmpty()) continue
                            add(Category(id = cid, name = c.optString("name")))
                        }
                    }
                    if (cats.isNotEmpty()) {
                        add(CategoryGroup(id = gid, name = g.optString("name"), categories = cats))
                    }
                }
            }
            if (groups.isEmpty()) error("抖音分区表为空")
            categoriesCache = groups
            return groups
        }
    }

    // ---- Live list --------------------------------------------------------

    /** Page of rooms for a partition id (`FetchLiveList`). */
    fun fetchLiveList(
        cateId: String,
        page: Int = 1,
        limit: Int = DouyinConstants.LIST_PAGE_SIZE,
    ): StreamerPage {
        val (partitionType, partition) = splitPartitionId(cateId)
        val safePage = max(1, page)
        val pageSize = when {
            limit <= 0 || limit > DouyinConstants.LIST_PAGE_SIZE -> DouyinConstants.LIST_PAGE_SIZE
            else -> limit
        }
        val offset = (safePage - 1) * pageSize

        val params = listOf(
            "aid" to DouyinConstants.PARAM_AID,
            "app_name" to "douyin_web",
            "live_id" to DouyinConstants.PARAM_LIVE_ID,
            "device_platform" to "web",
            "language" to "zh-CN",
            "enter_from" to "web_homepage_hot",
            "cookie_enabled" to "true",
            "screen_width" to "1920",
            "screen_height" to "1080",
            "browser_language" to "zh-CN",
            "browser_platform" to "Win32",
            "browser_name" to "Chrome",
            "browser_version" to "116.0.0.0",
            "count" to pageSize.toString(),
            "offset" to offset.toString(),
            "partition" to partition,
            "partition_type" to partitionType,
            "req_from" to "2",
            "msToken" to ABogus.generateMsToken(),
        )

        val payload = getJson(signedUrl(DouyinConstants.URL_PARTITION_V2, params))
        val status = payload.optInt("status_code", -1)
        if (status != 0) {
            throw IllegalStateException("抖音分区列表返回 status_code=$status")
        }
        val data = payload.opt("data")
        val wrapper = when (data) {
            is JSONObject -> data
            else -> JSONObject()
        }
        val rooms = wrapper.optJSONArray("data") ?: JSONArray()
        val items = buildList {
            for (i in 0 until rooms.length()) {
                mapPartitionRoom(rooms.optJSONObject(i))?.let { add(it) }
            }
        }
        val hasMore = wrapper.optBoolean("has_more") || items.size >= pageSize
        return StreamerPage(items = items, page = safePage, hasMore = hasMore)
    }

    private fun mapPartitionRoom(raw: JSONObject?): Streamer? {
        if (raw == null) return null
        val webRid = JsonUtil.asString(raw.opt("web_rid"))
        if (webRid.isEmpty()) return null
        val room = raw.optJSONObject("room") ?: JSONObject()
        val owner = room.optJSONObject("owner") ?: JSONObject()
        val stats = room.optJSONObject("stats") ?: JSONObject()
        var viewerText = stats.optString("user_count_str")
        if (viewerText.isEmpty()) viewerText = stats.optString("total_user_str")
        return Streamer(
            id = webRid,
            platform = LivePlatform.DOUYIN.id,
            nickname = owner.optString("nickname"),
            title = room.optString("title"),
            cover = firstUrl(room.optJSONObject("cover")),
            avatar = firstUrl(owner.optJSONObject("avatar_thumb")),
            viewers = parseViewerText(viewerText),
            categoryName = "",
            isLive = true,
        )
    }

    // ---- Room / stream ----------------------------------------------------

    /** Light room card (`Streamer` / `FetchRoomInfo`). */
    fun fetchRoomInfo(roomId: String): Streamer {
        val bundle = resolveRoom(roomId)
        val room = bundle.room
        return Streamer(
            id = bundle.webRid,
            platform = LivePlatform.DOUYIN.id,
            nickname = room.optJSONObject("owner")?.optString("nickname").orEmpty(),
            title = room.optString("title"),
            cover = firstUrl(room.optJSONObject("cover")),
            avatar = firstUrl(room.optJSONObject("owner")?.optJSONObject("avatar_thumb")),
            viewers = viewersOf(room),
            categoryName = "",
            isLive = isRoomLive(room),
        )
    }

    /**
     * Playback entry: room meta + quality ladder (`StreamInfo`).
     * Douyin has no CDN lines — [StreamInfo.lines] stays empty.
     */
    fun streamInfo(roomId: String, line: String = ""): StreamInfo {
        val bundle = resolveRoom(roomId)
        val room = bundle.room
        val info = StreamInfo(
            roomId = bundle.webRid,
            platform = LivePlatform.DOUYIN.id,
            title = room.optString("title"),
            nickname = room.optJSONObject("owner")?.optString("nickname").orEmpty(),
            avatar = firstUrl(room.optJSONObject("owner")?.optJSONObject("avatar_thumb")),
            isLive = isRoomLive(room),
            variants = emptyList(),
            lines = emptyList(),
        )
        if (!info.isLive) return info

        val streamUrl = room.optJSONObject("stream_url")
        val streams = parseStreamData(streamUrl)
        val ladder = orderForPlayback(streams, pickQualities(room))
        val variants = ladder.map { q ->
            StreamVariant(
                id = q.id,
                label = q.label,
                url = enforceHttps(q.url),
                format = q.format.ifEmpty { "flv" },
            )
        }
        if (variants.isEmpty()) {
            throw IllegalStateException("抖音房间 ${bundle.webRid} 没有可用清晰度")
        }
        @Suppress("UNUSED_EXPRESSION")
        line
        return info.copy(variants = variants)
    }

    /** Single playable URL (`StreamURL`). */
    fun streamUrl(roomId: String, quality: String = "", line: String = ""): String {
        val info = streamInfo(roomId, line)
        if (!info.isLive || info.variants.isEmpty()) {
            throw IllegalStateException("抖音房间 $roomId 当前未开播")
        }
        val target = quality.trim()
        if (target.isEmpty()) return info.variants.first().url
        for (v in info.variants) {
            if (v.id.equals(target, true) || v.label == target) return v.url
        }
        return info.variants.first().url
    }

    // ---- Resolve web_rid → internal room_id → reflow ----------------------

    private data class RoomBundle(
        val webRid: String,
        val internalId: String,
        val room: JSONObject,
    )

    private fun resolveRoom(roomId: String): RoomBundle {
        val webRid = normalizeRoomId(roomId)
        if (webRid.isEmpty()) throw IllegalArgumentException("抖音房间号无效: \"$roomId\"")
        val html = fetchRoomPage(webRid)
        val internalId = extractInternalRoomId(html)
        if (internalId.isEmpty()) {
            throw IllegalStateException(
                "抖音房间 $webRid 页面里没有找到内部 roomId（房间可能不存在或页面结构变更）",
            )
        }
        val room = fetchReflow(internalId)
        return RoomBundle(webRid, internalId, room)
    }

    private fun fetchRoomPage(webRid: String): String {
        val target = DouyinConstants.URL_LIVE_HOME + webRid
        val headers = mutableMapOf(
            "User-Agent" to DouyinConstants.DESKTOP_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Referer" to DouyinConstants.URL_LIVE_HOME,
        )
        cookieHeader().takeIf { it.isNotEmpty() }?.let { headers["Cookie"] = it }
        http.get(target, headers).use { resp ->
            mergeSetCookies(resp.headers("Set-Cookie"))
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} fetching room page $webRid")
            }
            return resp.body?.string().orEmpty()
        }
    }

    private fun fetchReflow(internalRoomId: String): JSONObject {
        val params = listOf(
            "type_id" to DouyinConstants.REFLOW_TYPE_ID,
            "live_id" to DouyinConstants.PARAM_LIVE_ID,
            "version_code" to DouyinConstants.REFLOW_VERSION_CODE,
            "app_id" to DouyinConstants.REFLOW_APP_ID,
            "room_id" to internalRoomId,
            "msToken" to ABogus.generateMsToken(),
        )
        val headers = mutableMapOf(
            "User-Agent" to DouyinConstants.DESKTOP_UA,
            "Referer" to DouyinConstants.URL_LIVE_HOME,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )
        cookieHeader().takeIf { it.isNotEmpty() }?.let { headers["Cookie"] = it }
        val payload = http.getJson(signedUrl(DouyinConstants.URL_REFLOW, params), headers)
        val data = payload.optJSONObject("data") ?: JSONObject()
        val room = data.optJSONObject("room")
            ?: throw IllegalStateException(
                "抖音房间数据为空（status_code=${payload.optInt("status_code")}）",
            )
        return room
    }

    // ---- Quality ladder ---------------------------------------------------

    private data class TierStream(
        val flv: String = "",
        val hls: String = "",
        val codec: String = "",
        val bitrate: Long = 0,
        val resolution: String = "",
    ) {
        fun isHevc(): Boolean =
            when (codec.trim().lowercase()) {
                "h265", "265", "hevc", "hvc1" -> true
                else -> false
            }
    }

    private data class FlvQuality(
        val key: String,
        val label: String,
        val id: String,
        val url: String,
        val format: String,
    )

    private data class TierDef(val key: String, val label: String, val id: String)

    private val douyinTiers = listOf(
        TierDef("origin", "原画", "source"),
        TierDef("uhd", "蓝光", "bluray"),
        TierDef("hd", "超清", "high"),
        TierDef("sd", "高清", "standard"),
        TierDef("ld", "标清", "low"),
    )

    private val flvPullKeyTier = mapOf(
        "FULL_HD1" to "uhd",
        "HD1" to "hd",
        "SD1" to "ld",
        "SD2" to "sd",
    )

    private fun parseStreamData(streamUrl: JSONObject?): Map<String, TierStream> {
        if (streamUrl == null) return emptyMap()
        val raw = streamDataJson(streamUrl)
        if (raw.isEmpty()) return emptyMap()
        return try {
            val outer = JSONObject(raw)
            val data = outer.optJSONObject("data") ?: return emptyMap()
            val out = linkedMapOf<String, TierStream>()
            val keys = data.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = data.optJSONObject(key) ?: continue
                val main = entry.optJSONObject("main") ?: continue
                val flv = main.optString("flv").trim()
                val hls = main.optString("hls").trim()
                if (flv.isEmpty() && hls.isEmpty()) continue
                var codec = ""
                var bitrate = 0L
                var resolution = ""
                val params = main.optString("sdk_params").trim()
                if (params.isNotEmpty()) {
                    runCatching {
                        val p = JSONObject(params)
                        codec = p.optString("VCodec")
                        bitrate = p.optDouble("vbitrate", 0.0).toLong()
                        resolution = p.optString("resolution")
                    }
                }
                out[key] = TierStream(flv = flv, hls = hls, codec = codec, bitrate = bitrate, resolution = resolution)
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "解析 stream_data 失败（清晰度退回 flv_pull_url）: ${e.message}")
            emptyMap()
        }
    }

    private fun streamDataJson(streamUrl: JSONObject): String {
        val liveCore = streamUrl.optJSONObject("live_core_sdk_data")
        val pullData = liveCore?.optJSONObject("pull_data")
        val fromCore = pullData?.optString("stream_data")?.trim().orEmpty()
        if (fromCore.isNotEmpty()) return fromCore
        val pullDatas = streamUrl.optJSONObject("pull_datas") ?: return ""
        val keys = pullDatas.keys()
        while (keys.hasNext()) {
            val entry = pullDatas.optJSONObject(keys.next()) ?: continue
            val raw = entry.optString("stream_data").trim()
            if (raw.isNotEmpty()) return raw
        }
        return ""
    }

    private fun pickQualities(room: JSONObject): List<FlvQuality> {
        val su = room.optJSONObject("stream_url") ?: return emptyList()
        val streams = parseStreamData(su)
        val pullUrl = su.optJSONObject("flv_pull_url")
        val hlsMap = su.optJSONObject("hls_pull_url_map")
        if (streams.isEmpty() && (pullUrl == null || pullUrl.length() == 0) &&
            (hlsMap == null || hlsMap.length() == 0)
        ) {
            return emptyList()
        }

        val byTier = linkedMapOf<String, TierStream>()
        for ((tier, stream) in streams) {
            if (tier == "ao") continue
            byTier[tier] = stream
        }
        if (pullUrl != null) {
            val keys = pullUrl.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val u = pullUrl.optString(key).trim()
                if (u.isEmpty()) continue
                var tier = tierFromUrl(u)
                if (tier.isEmpty()) tier = flvPullKeyTier[key].orEmpty()
                if (tier.isEmpty() || byTier.containsKey(tier)) continue
                byTier[tier] = TierStream(flv = u)
            }
        }
        if (hlsMap != null) {
            val keys = hlsMap.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val u = hlsMap.optString(key).trim()
                if (u.isEmpty()) continue
                val tier = tierFromUrl(u)
                if (tier.isEmpty() || byTier.containsKey(tier)) continue
                byTier[tier] = TierStream(hls = u)
            }
        }

        val out = mutableListOf<FlvQuality>()
        for (t in douyinTiers) {
            tierQuality(t.key, t.label, t.id, byTier[t.key])?.let { out += it }
        }
        if (out.isEmpty() && pullUrl != null) {
            val keys = pullUrl.keys().asSequence().toList().sorted()
            for (k in keys) {
                val u = pullUrl.optString(k).trim()
                if (u.isEmpty()) continue
                out += FlvQuality(key = k, label = k, id = k.lowercase(), url = u, format = "flv")
            }
            if (out.isNotEmpty()) {
                out[0] = out[0].copy(label = "原画")
            }
        }
        return out
    }

    private fun tierQuality(tier: String, label: String, id: String, stream: TierStream?): FlvQuality? {
        if (stream == null) return null
        val hevc = stream.isHevc()
        var url = stream.flv
        var format = "flv"
        if (hevc && stream.hls.isNotEmpty()) {
            // FLV HEVC (CodecID 12) is unreliable on many decoders — prefer HLS.
            url = stream.hls
            format = "hls"
        }
        if (url.isEmpty()) {
            url = stream.hls
            format = "hls"
        }
        if (url.isEmpty()) return null
        val shown = if (hevc) "$label · H.265" else label
        return FlvQuality(key = tier, label = shown, id = id, url = url, format = format)
    }

    /**
     * Prefer an H.264-playable default when origin is HEVC (Go `orderForPlayback`).
     * Optionally sniffs `&codec=h264` FLV CodecID before advertising a sibling.
     */
    private fun orderForPlayback(
        streams: Map<String, TierStream>,
        ladder: List<FlvQuality>,
    ): List<FlvQuality> {
        if (ladder.isEmpty()) return ladder
        val origin = streams["origin"]
        if (origin == null || !origin.isHevc()) return ladder

        val sibling = h264OriginForHevc(origin)
        if (sibling != null) {
            val out = ArrayList<FlvQuality>(ladder.size + 1)
            out += sibling
            for (q in ladder) {
                out += if (q.key == "origin") {
                    q.copy(id = "source-h265", label = "原画 · H.265")
                } else {
                    q
                }
            }
            return out
        }

        // No H.264 origin: demote HEVC origin out of default slot.
        val idx = ladder.indexOfFirst { it.key == "origin" }
        if (idx < 0 || idx == 1) return ladder
        val hevcQ = ladder[idx].copy(label = "原画 · H.265")
        val rest = ladder.toMutableList().also { it.removeAt(idx) }
        val pos = if (rest.isEmpty()) 0 else 1.coerceAtMost(rest.size)
        rest.add(pos, hevcQ)
        return rest
    }

    private fun h264OriginForHevc(origin: TierStream): FlvQuality? {
        if (!origin.isHevc() || origin.flv.isEmpty()) return null
        val probe = origin.flv + "&codec=h264"
        val codec = runCatching { sniffFlvCodec(probe) }.getOrElse {
            Log.w(TAG, "探测 H.264 原画失败（保留原样）: ${it.message}")
            return null
        }
        if (codec != "h264") {
            Log.w(TAG, "codec=h264 仍返回 $codec，不额外提供 H.264 原画")
            return null
        }
        Log.i(TAG, "原画是 H.265，但 codec=h264 能拿到 H.264 原画，已并行提供")
        return FlvQuality(
            key = "origin-h264",
            label = "原画 · H.264",
            id = "source",
            url = probe,
            format = "flv",
        )
    }

    // ---- HTTP helpers -----------------------------------------------------

    private fun getJson(url: String): JSONObject {
        val headers = mutableMapOf(
            "User-Agent" to DouyinConstants.DESKTOP_UA,
            "Referer" to DouyinConstants.REFERER,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            // Prefer identity to avoid br/gzip surprises on TV OkHttp builds.
            "Accept-Encoding" to "identity",
        )
        cookieHeader().takeIf { it.isNotEmpty() }?.let { headers["Cookie"] = it }
        http.get(url, headers).use { resp ->
            mergeSetCookies(resp.headers("Set-Cookie"))
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: ${body.take(200)}")
            }
            return JSONObject(body.ifBlank { "{}" })
        }
    }

    companion object {
        private const val TAG = "Douyin"

        private val roomIdPatterns = listOf(
            Regex("""\\"roomId\\":\\"(\d{10,})\\""""),
            Regex(""""roomId":"(\d{10,})""""),
            Regex("""\\"room_id\\":\\"(\d{10,})\\""""),
        )

        private const val FLV_CODEC_AVC = 7
        private const val FLV_CODEC_HEVC = 12

        /** Split `1_1010032` / `/category/…_1_1010032` → (type, partition). */
        fun splitPartitionId(cateId: String): Pair<String, String> {
            var trimmed = cateId.trim()
            val catIdx = trimmed.lastIndexOf("/category/")
            if (catIdx >= 0) {
                trimmed = trimmed.substring(catIdx + "/category/".length)
            }
            trimmed = trimmed.removePrefix("/")
            val parts = trimmed.split('_')
            require(parts.size >= 2) {
                "抖音分区 id 格式应为 partition_type_partition，实际 \"$cateId\""
            }
            val partitionType = parts[parts.size - 2]
            val partition = parts[parts.size - 1]
            require(partitionType.isNotEmpty() && partition.isNotEmpty()) {
                "抖音分区 id 不完整: \"$cateId\""
            }
            return partitionType to partition
        }

        fun normalizeRoomId(input: String): String {
            var s = input.trim()
            if (s.isEmpty()) return ""
            val q = s.indexOf('?')
            if (q >= 0) {
                val query = s.substring(q + 1)
                for (kv in query.split('&')) {
                    for (key in listOf("room_id=", "roomId=", "web_rid=", "webId=")) {
                        if (kv.startsWith(key)) {
                            var v = kv.removePrefix(key)
                            val cut = v.indexOfAny(charArrayOf('&', '#'))
                            if (cut >= 0) v = v.substring(0, cut)
                            if (v.isNotEmpty()) return keepDigits(v)
                        }
                    }
                }
                s = s.substring(0, q)
            }
            val hash = s.indexOf('#')
            if (hash >= 0) s = s.substring(0, hash)
            s = s.trimEnd('/')
            val slash = s.lastIndexOf('/')
            if (slash >= 0) s = s.substring(slash + 1)
            return keepDigits(s)
        }

        fun keepDigits(s: String): String = buildString {
            for (ch in s) if (ch in '0'..'9') append(ch)
        }

        fun extractInternalRoomId(html: String): String {
            for (re in roomIdPatterns) {
                val m = re.find(html) ?: continue
                val id = m.groupValues.getOrNull(1).orEmpty()
                if (id.isNotEmpty()) return id
            }
            return ""
        }

        fun firstUrl(obj: JSONObject?): String {
            if (obj == null) return ""
            val list = obj.optJSONArray("url_list") ?: return ""
            return if (list.length() > 0) list.optString(0) else ""
        }

        fun viewersOf(room: JSONObject): Long {
            val viewStats = room.optJSONObject("room_view_stats")
            val display = viewStats?.optLong("display_value") ?: 0L
            if (display > 0) return display
            val stats = room.optJSONObject("stats") ?: JSONObject()
            var text = stats.optString("user_count_str")
            if (text.isEmpty() || text == "0") text = stats.optString("total_user_str")
            return parseViewerText(text)
        }

        fun isRoomLive(room: JSONObject): Boolean {
            // status 2 = live, 4 = ended; 0 → fall back to having pull URLs.
            return when (room.optInt("status")) {
                2 -> true
                0 -> {
                    val su = room.optJSONObject("stream_url")
                    val flv = su?.optJSONObject("flv_pull_url")
                    (flv != null && flv.length() > 0) ||
                        !streamDataJsonStatic(su).isNullOrEmpty()
                }
                else -> false
            }
        }

        private fun streamDataJsonStatic(streamUrl: JSONObject?): String {
            if (streamUrl == null) return ""
            val liveCore = streamUrl.optJSONObject("live_core_sdk_data")
            val pullData = liveCore?.optJSONObject("pull_data")
            val fromCore = pullData?.optString("stream_data")?.trim().orEmpty()
            if (fromCore.isNotEmpty()) return fromCore
            return ""
        }

        fun tierFromUrl(raw: String): String {
            var u = raw
            val cut = u.indexOfAny(charArrayOf('?', '#'))
            if (cut >= 0) u = u.substring(0, cut)
            val segments = u.trim('/').split('/')
            val start = (segments.size - 2).coerceAtLeast(0)
            for (i in segments.lastIndex downTo start) {
                var seg = segments[i]
                seg = seg.removeSuffix(".flv").removeSuffix(".m3u8")
                val idx = seg.lastIndexOf('_')
                if (idx < 0) continue
                when (val suffix = seg.substring(idx + 1)) {
                    "or4", "origin" -> return "origin"
                    "uhd", "hd", "sd", "ld", "md" -> return suffix
                }
            }
            return ""
        }

        fun enforceHttps(raw: String): String =
            if (raw.startsWith("http://")) "https://" + raw.removePrefix("http://") else raw

        /**
         * Signed URL: encode params in given order, sign that exact query, append a_bogus.
         * Sign string and wire string must be identical (Go `signedURL`).
         */
        fun signedUrl(endpoint: String, params: List<Pair<String, String>>): String {
            val query = params.joinToString("&") { (k, v) ->
                urlEncode(k) + "=" + urlEncode(v)
            }
            val sign = ABogus.generateABogus(query, DouyinConstants.DESKTOP_UA)
            return endpoint + "?" + query + "&a_bogus=" + urlEncode(sign)
        }

        fun urlEncode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

        /**
         * Range-GET the first FLV tags and read video CodecID (Go `sniffFLVCodec`).
         * Do **not** use HEAD — Douyin HLS origin often answers 405.
         */
        fun sniffFlvCodec(rawUrl: String): String {
            require(rawUrl.isNotBlank()) { "空地址" }
            val client = OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .proxy(java.net.Proxy.NO_PROXY)
                .build()
            val req = Request.Builder()
                .url(rawUrl)
                .header("User-Agent", DouyinConstants.DESKTOP_UA)
                .header("Referer", DouyinConstants.URL_LIVE_HOME)
                .header("Range", "bytes=0-65535")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code != 200 && resp.code != 206) {
                    throw IOException("HTTP ${resp.code}")
                }
                val buf = resp.body?.bytes()?.take(64 * 1024)?.toByteArray()
                    ?: throw IOException("空响应")
                if (buf.size < 13 || String(buf, 0, 3) != "FLV") {
                    throw IOException("不是 FLV 流")
                }
                var off = 13
                while (off + 11 <= buf.size) {
                    val tagType = buf[off].toInt() and 0xFF
                    val dataSize = ((buf[off + 1].toInt() and 0xFF) shl 16) or
                        ((buf[off + 2].toInt() and 0xFF) shl 8) or
                        (buf[off + 3].toInt() and 0xFF)
                    val dataStart = off + 11
                    val dataEnd = dataStart + dataSize
                    if (dataSize <= 0 || dataEnd > buf.size) break
                    if (tagType == 9) {
                        val codec = buf[dataStart].toInt() and 0x0f
                        return when (codec) {
                            FLV_CODEC_AVC -> "h264"
                            FLV_CODEC_HEVC -> "h265"
                            else -> throw IOException("未知 CodecID $codec")
                        }
                    }
                    off = dataEnd + 4
                }
                throw IOException("前 64KB 里没有 video tag")
            }
        }
    }
}

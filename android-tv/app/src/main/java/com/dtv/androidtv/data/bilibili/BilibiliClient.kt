package com.dtv.androidtv.data.bilibili

import com.dtv.androidtv.data.douyu.JsonUtil
import com.dtv.androidtv.data.model.Category
import com.dtv.androidtv.data.model.CategoryGroup
import com.dtv.androidtv.data.model.LineOption
import com.dtv.androidtv.data.model.LivePlatform
import com.dtv.androidtv.data.model.QRLoginCode
import com.dtv.androidtv.data.model.QRLoginResult
import com.dtv.androidtv.data.model.QRLoginStatus
import com.dtv.androidtv.data.model.StreamInfo
import com.dtv.androidtv.data.model.StreamVariant
import com.dtv.androidtv.data.model.Streamer
import com.dtv.androidtv.data.model.StreamerPage
import com.dtv.androidtv.data.net.HttpJsonClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Kotlin contract-port of Go `internal/platforms/bilibili.Client`.
 *
 * Scope: categories, live list (w_webid + list sign), room_init / getInfoByRoom,
 * getRoomPlayInfo → **HLS only**, Web QR login + cookie session.
 * Danmaku intentionally omitted.
 *
 * Risk note: list API code **-352** means captcha / stale `w_webid`. We scrape
 * `access_id` from live HTML, cache ~30m, and force-refresh + retry once on -352.
 * A real SESSDATA cookie (via [setCookie] / QR login) improves resilience.
 */
class BilibiliClient(
    private val http: HttpJsonClient = HttpJsonClient(),
) {
    private val stateLock = Any()
    private var wWebId: String = ""
    private var wWebIdAtMs: Long = 0L

    private val wbiFetchLock = Any()
    private var wbiImg: String = ""
    private var wbiSub: String = ""
    private var wbiAtMs: Long = 0L
    private var wbiErr: Exception? = null

    /**
     * Browser Cookie string from QR login (`SESSDATA=…; bili_jct=…; …`).
     * Empty means anonymous — headers fall back to `buvid3=i;`.
     */
    @Volatile
    private var cookie: String = ""

    /** Apply login Cookie (empty clears session). Mirrors Go `SetCookie`. */
    fun setCookie(raw: String) {
        cookie = raw.trim()
        // Go also clears the HTTP cookie jar; OkHttp here has no jar by default.
    }

    /** Current login Cookie, or empty when anonymous. */
    fun cookie(): String = cookie

    // ---- QR login ---------------------------------------------------------

    /**
     * Account snapshot from `/x/web-interface/nav` (mirrors Go `BilibiliProfile`).
     * [loggedIn]=false is normal when anonymous / expired — not an error.
     */
    data class Profile(
        val loggedIn: Boolean,
        val userName: String = "",
        val avatar: String = "",
    )

    /** Request a Web QR login payload (`url` + `qrcode_key`). */
    fun qrLoginGenerate(): QRLoginCode {
        http.get(BilibiliConstants.URL_QR_GENERATE, qrLoginHeaders()).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("申请登录二维码失败: HTTP ${response.code}")
            }
            val payload = JSONObject(body.ifBlank { "{}" })
            if (payload.optInt("code") != 0) {
                throw IllegalStateException(
                    "申请登录二维码失败: code=${payload.optInt("code")} " +
                        "(${payload.optString("message")})",
                )
            }
            val data = payload.optJSONObject("data") ?: JSONObject()
            val url = data.optString("url").trim()
            val key = data.optString("qrcode_key").trim()
            if (url.isEmpty() || key.isEmpty()) {
                throw IllegalStateException("申请登录二维码失败: 上游未返回二维码内容")
            }
            return QRLoginCode(
                url = url,
                key = key,
                expiresIn = BilibiliConstants.QR_EXPIRES_IN_SEC,
            )
        }
    }

    /**
     * One poll of QR scan state. On success merges Set-Cookie + `data.url` query
     * into an ordered Cookie string, applies via [setCookie], and fills profile.
     */
    fun qrLoginPoll(key: String): QRLoginResult {
        val trimmed = key.trim()
        require(trimmed.isNotEmpty()) { "缺少 qrcode_key" }

        val target = BilibiliConstants.URL_QR_POLL +
            "?qrcode_key=${urlEncode(trimmed)}" +
            "&source=${BilibiliConstants.QR_POLL_SOURCE}"

        http.get(target, qrLoginHeaders()).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("查询扫码状态失败: HTTP ${response.code}")
            }
            val payload = JSONObject(body.ifBlank { "{}" })
            if (payload.optInt("code") != 0) {
                throw IllegalStateException(
                    "查询扫码状态失败: code=${payload.optInt("code")} " +
                        "(${payload.optString("message")})",
                )
            }
            val data = payload.optJSONObject("data") ?: JSONObject()
            return when (data.optInt("code")) {
                0 -> {
                    val collected = linkedMapOf<String, String>()
                    mergeSetCookies(collected, response.headers("Set-Cookie"))
                    mergeCookieQuery(collected, data.optString("url"))
                    val built = buildCookieString(collected)
                    if (built.isEmpty()) {
                        return QRLoginResult(
                            status = QRLoginStatus.ERROR,
                            message = "登录成功但没拿到 Cookie，请重试",
                        )
                    }
                    setCookie(built)
                    var result = QRLoginResult(
                        status = QRLoginStatus.SUCCESS,
                        cookie = built,
                        message = "登录成功",
                    )
                    runCatching { loginProfile() }.getOrNull()?.let { profile ->
                        if (profile.loggedIn) {
                            result = result.copy(
                                userName = profile.userName,
                                avatar = profile.avatar,
                            )
                        }
                    }
                    result
                }
                86101 -> QRLoginResult(
                    status = QRLoginStatus.PENDING,
                    message = "等待手机扫码",
                )
                86090 -> QRLoginResult(
                    status = QRLoginStatus.SCANNED,
                    message = "已扫码，请在手机上确认登录",
                )
                86038 -> QRLoginResult(
                    status = QRLoginStatus.EXPIRED,
                    message = "二维码已失效，请刷新",
                )
                else -> QRLoginResult(
                    status = QRLoginStatus.ERROR,
                    message = "未知的扫码状态：${data.optInt("code")} ${data.optString("message")}",
                )
            }
        }
    }

    /**
     * Validate current Cookie via nav; returns [Profile.loggedIn]=false when
     * anonymous or expired (not an error).
     */
    fun loginProfile(): Profile {
        val payload = getJson(BilibiliConstants.URL_LOGIN_NAV)
        val data = payload.optJSONObject("data") ?: JSONObject()
        if (payload.optInt("code") != 0 || !data.optBoolean("isLogin")) {
            return Profile(loggedIn = false)
        }
        return Profile(
            loggedIn = true,
            userName = data.optString("uname"),
            avatar = httpsUrl(data.optString("face")),
        )
    }

    /**
     * Server-side logout via passport `exit/v2` (needs `bili_jct` as CSRF), then
     * clear local Cookie. Local clear always runs even if the POST fails.
     */
    fun logout() {
        val current = cookie()
        val csrf = cookieValue(current, "bili_jct")
        var logoutErr: Exception? = null
        if (current.isNotEmpty() && csrf.isNotEmpty()) {
            val headers = headers().toMutableMap()
            headers["Referer"] = BilibiliConstants.WWW_REFERER
            headers["Origin"] = BilibiliConstants.WWW_ORIGIN
            try {
                http.postForm(
                    BilibiliConstants.URL_LOGOUT,
                    mapOf("biliCSRF" to csrf),
                    headers,
                ).use { response ->
                    val body = response.body?.string().orEmpty()
                    val payload = JSONObject(body.ifBlank { "{}" })
                    val code = payload.optInt("code")
                    // -101 = already logged out — treat as OK (matches Go).
                    if (code != 0 && code != -101) {
                        logoutErr = IllegalStateException(
                            "退出登录失败: code=$code (${payload.optString("message")})",
                        )
                    }
                }
            } catch (e: Exception) {
                logoutErr = e
            }
        }
        setCookie("")
        if (logoutErr != null) throw logoutErr!!
    }

    // ---- Categories -------------------------------------------------------

    fun fetchCategories(): List<CategoryGroup> {
        synchronized(areaCacheLock) {
            val cached = areaCache
            if (cached != null && System.currentTimeMillis() - areaCachedAtMs < BilibiliConstants.AREA_CACHE_TTL_MS) {
                return cached
            }
        }
        val groups = fetchAreaList()
        synchronized(areaCacheLock) {
            areaCache = groups
            areaCachedAtMs = System.currentTimeMillis()
        }
        return groups
    }

    private fun fetchAreaList(): List<CategoryGroup> {
        val payload = getJson(
            BilibiliSign.buildUrl(BilibiliConstants.URL_AREA_LIST, "show_pinyin=1"),
        )
        if (payload.optInt("code") != 0) {
            throw IllegalStateException(
                "bilibili Area/getList 返回 code=${payload.optInt("code")}",
            )
        }
        val data = payload.optJSONArray("data")
            ?: throw IllegalStateException("分区表为空")
        val groups = mutableListOf<CategoryGroup>()
        for (i in 0 until data.length()) {
            val g = data.optJSONObject(i) ?: continue
            val list = g.optJSONArray("list") ?: continue
            if (list.length() == 0) continue
            val groupId = JsonUtil.asString(g.opt("id"))
            val categories = mutableListOf<Category>()
            for (j in 0 until list.length()) {
                val sub = list.optJSONObject(j) ?: continue
                val childId = JsonUtil.asString(sub.opt("id"))
                if (childId.isEmpty()) continue
                var parentId = JsonUtil.asString(sub.opt("parent_id"))
                if (parentId.isEmpty()) parentId = groupId
                categories += Category(
                    id = "${parentId}_$childId",
                    name = sub.optString("name").trim(),
                )
            }
            if (categories.isEmpty()) continue
            groups += CategoryGroup(
                id = groupId,
                name = g.optString("name").trim(),
                categories = categories,
            )
        }
        if (groups.isEmpty()) throw IllegalStateException("分区表为空")
        return groups
    }

    // ---- Live list --------------------------------------------------------

    /**
     * Page of live rooms for `parent_child` area id.
     *
     * -352: human-check / stale w_webid — refresh scrape and retry once.
     */
    fun fetchLiveList(
        areaId: String,
        page: Int = 1,
        limit: Int = BilibiliConstants.AREA_PAGE_SIZE,
    ): StreamerPage {
        var (parentId, childId) = splitAreaId(areaId)
        if (parentId.isEmpty()) {
            parentId = lookupParentArea(childId)
            if (parentId.isEmpty()) {
                throw IllegalArgumentException(
                    "分区 \"$areaId\" 缺少父分区 id，请传 “父_子” 形式（例如 2_86）",
                )
            }
        }
        val safePage = max(1, page)
        // limit is display-only; upstream page size is fixed.
        @Suppress("UNUSED_VARIABLE")
        val unusedLimit = limit

        return try {
            fetchLiveListOnce(parentId, childId, safePage, forceRefreshWebId = false)
        } catch (e: BilibiliApiException) {
            if (e.code != -352L) throw e
            // Force-refresh w_webid then retry once.
            fetchLiveListOnce(parentId, childId, safePage, forceRefreshWebId = true)
        }
    }

    private fun fetchLiveListOnce(
        parentId: String,
        childId: String,
        page: Int,
        forceRefreshWebId: Boolean,
    ): StreamerPage {
        val wWebIdValue = wWebIdValue(force = forceRefreshWebId)
        // Param order must match Go: signing concatenates in this order.
        val params = listOf(
            "area_id" to childId,
            "page" to page.toString(),
            "parent_area_id" to parentId,
            "platform" to "web",
            "sort_type" to "",
            "vajra_business_key" to "",
            "w_webid" to wWebIdValue,
            "web_location" to "444.253",
        )
        val (query, wRid) = BilibiliSign.listSignQuery(params, BilibiliSign.nowSeconds())
        val target = BilibiliSign.buildUrl(
            BilibiliConstants.URL_LIVE_LIST,
            "$query&w_rid=$wRid",
        )
        val payload = getJson(target)
        val code = payload.optLong("code")
        if (code != 0L) {
            if (code == -352L) {
                throw BilibiliApiException(
                    code = -352L,
                    apiMessage = "人机校验拦截：w_webid 可能已失效",
                    endpoint = "second/getList",
                )
            }
            throw BilibiliApiException(
                code = code,
                apiMessage = payload.optString("message"),
                endpoint = "second/getList",
            )
        }
        val data = payload.optJSONObject("data") ?: JSONObject()
        val list = data.optJSONArray("list") ?: JSONArray()
        val items = buildList {
            for (i in 0 until list.length()) {
                mapLiveListItem(list.optJSONObject(i))?.let { add(it) }
            }
        }
        var hasMore = jsonBool(data.opt("has_more"))
        if (!data.has("has_more") && items.size >= BilibiliConstants.AREA_PAGE_SIZE) {
            hasMore = true
        }
        return StreamerPage(items = items, page = page, hasMore = hasMore, totalPages = 0)
    }

    private fun mapLiveListItem(raw: JSONObject?): Streamer? {
        if (raw == null) return null
        val roomId = JsonUtil.asString(raw.opt("roomid"))
        if (roomId.isEmpty() || roomId == "0") return null
        var title = JsonUtil.asString(raw.opt("title"))
        if (title.isEmpty()) title = JsonUtil.asString(raw.opt("area_name"))
        return Streamer(
            id = roomId,
            platform = LivePlatform.BILIBILI.id,
            nickname = JsonUtil.asString(raw.opt("uname")),
            title = stripEmTags(title).trim(),
            cover = httpsUrl(
                firstNonEmpty(
                    JsonUtil.asString(raw.opt("cover")),
                    JsonUtil.asString(raw.opt("user_cover")),
                    JsonUtil.asString(raw.opt("system_cover")),
                ),
            ),
            avatar = httpsUrl(JsonUtil.asString(raw.opt("face"))),
            viewers = JsonUtil.asLong(raw.opt("online")),
            categoryName = firstNonEmpty(
                JsonUtil.asString(raw.opt("area_name")),
                JsonUtil.asString(raw.opt("area_v2_name")),
                JsonUtil.asString(raw.opt("parent_name")),
            ),
            isLive = true,
        )
    }

    private fun lookupParentArea(childId: String): String {
        val groups = runCatching { fetchCategories() }.getOrElse { return "" }
        for (g in groups) {
            for (c in g.categories) {
                val (parent, child) = splitAreaId(c.id)
                if (child == childId) return parent
            }
        }
        return ""
    }

    // ---- Room detail ------------------------------------------------------

    data class RoomInitResult(
        val realId: String,
        val liveStatus: Long,
        val title: String,
        val uname: String,
    )

    private data class RoomInfoData(
        val title: String,
        val nickname: String,
        val avatar: String,
        val cover: String,
        val viewers: Long,
        val areaName: String,
        val liveStatus: Long,
        val realId: String,
    )

    fun roomInit(roomId: String): RoomInitResult {
        val target = BilibiliSign.buildUrl(
            BilibiliConstants.URL_ROOM_INIT,
            "id=${BilibiliSign.queryEscape(roomId)}",
        )
        val payload = getJson(target)
        if (payload.optLong("code") != 0L) {
            throw BilibiliApiException(
                code = payload.optLong("code"),
                apiMessage = payload.optString("msg"),
                endpoint = "Room/room_init",
            )
        }
        val data = payload.optJSONObject("data") ?: JSONObject()
        var realId = JsonUtil.asString(data.opt("room_id"))
        if (realId.isEmpty()) realId = roomId
        return RoomInitResult(
            realId = realId,
            liveStatus = JsonUtil.asLong(data.opt("live_status")),
            title = data.optString("title"),
            uname = data.optString("uname"),
        )
    }

    private fun fetchRoomInfoData(roomId: String): RoomInfoData {
        val (imgKey, subKey) = wbiKeys()
        val query = BilibiliSign.wbiSignedQuery(
            listOf(
                "room_id" to roomId,
                "web_location" to "444.8",
            ),
            imgKey,
            subKey,
            BilibiliSign.nowSeconds(),
        )
        val payload = getJson(BilibiliSign.buildUrl(BilibiliConstants.URL_ROOM_INFO, query))
        if (payload.optLong("code") != 0L) {
            throw BilibiliApiException(
                code = payload.optLong("code"),
                apiMessage = payload.optString("msg"),
                endpoint = "getInfoByRoom",
            )
        }
        val data = payload.optJSONObject("data") ?: JSONObject()
        val room = data.optJSONObject("room_info") ?: JSONObject()
        val base = data.optJSONObject("anchor_info")
            ?.optJSONObject("base_info")
            ?: JSONObject()
        return RoomInfoData(
            title = room.optString("title"),
            nickname = base.optString("uname"),
            avatar = httpsUrl(base.optString("face")),
            cover = httpsUrl(
                firstNonEmpty(
                    room.optString("user_cover"),
                    room.optString("keyframe"),
                    room.optString("cover"),
                ),
            ),
            viewers = JsonUtil.asLong(room.opt("online")),
            areaName = firstNonEmpty(
                room.optString("area_name"),
                room.optString("parent_area_name"),
            ),
            liveStatus = JsonUtil.asLong(room.opt("live_status")),
            realId = firstNonEmpty(
                JsonUtil.asString(room.opt("room_id")),
                JsonUtil.asString(room.opt("short_id")),
            ),
        )
    }

    /** Card-level room meta for follow / recent refresh (`Streamer`). */
    fun fetchRoomInfo(roomId: String): Streamer {
        val normalized = normalizeRoomId(roomId)
        require(normalized.isNotEmpty()) { "房间号为空" }
        val init = roomInit(normalized)
        return try {
            val info = fetchRoomInfoData(init.realId)
            Streamer(
                id = normalized,
                platform = LivePlatform.BILIBILI.id,
                nickname = info.nickname,
                title = info.title,
                cover = info.cover,
                avatar = info.avatar,
                viewers = info.viewers,
                categoryName = info.areaName,
                isLive = isLiveStatus(info.liveStatus),
            )
        } catch (_: Exception) {
            Streamer(
                id = normalized,
                platform = LivePlatform.BILIBILI.id,
                nickname = init.uname,
                title = init.title,
                cover = "",
                avatar = "",
                viewers = 0L,
                categoryName = "",
                isLive = isLiveStatus(init.liveStatus),
            )
        }
    }

    // ---- Stream resolve (HLS only) ----------------------------------------

    private data class PlayUrl(
        val url: String,
        val format: String,
        val protocol: String,
        val qn: Long,
    )

    private data class StreamPlayInfo(
        val acceptQn: List<Long>,
        val qnDesc: Map<Long, String>,
        val currentQn: Long,
        val playUrls: List<PlayUrl>,
        val liveStatus: Long,
    )

    /**
     * One getRoomPlayInfo call. Upstream returns **one qn** per request.
     * We request all formats but [pickHlsOnly] discards FLV (404 without Referer proxy).
     */
    private fun fetchPlayInfo(roomId: String, qn: Long): StreamPlayInfo {
        var params = "room_id=${BilibiliSign.queryEscape(roomId)}" +
            "&protocol=0,1" +
            "&format=0,1,2" +
            "&codec=0" +
            "&platform=html5" +
            "&dolby=5" +
            "&panorama=1"
        if (qn > 0) params += "&qn=$qn"
        val payload = getJson(BilibiliSign.buildUrl(BilibiliConstants.URL_ROOM_PLAY_INFO, params))
        if (payload.optLong("code") != 0L) {
            throw BilibiliApiException(
                code = payload.optLong("code"),
                apiMessage = payload.optString("msg"),
                endpoint = "getRoomPlayInfo",
            )
        }
        val data = payload.optJSONObject("data") ?: JSONObject()
        val playurl = data.optJSONObject("playurl_info")
            ?.optJSONObject("playurl")
            ?: JSONObject()
        val qnDesc = mutableMapOf<Long, String>()
        val gQnDesc = playurl.optJSONArray("g_qn_desc") ?: JSONArray()
        for (i in 0 until gQnDesc.length()) {
            val item = gQnDesc.optJSONObject(i) ?: continue
            qnDesc[JsonUtil.asLong(item.opt("qn"))] = item.optString("desc")
        }
        val accept = linkedSetOf<Long>()
        val playUrls = mutableListOf<PlayUrl>()
        val streams = playurl.optJSONArray("stream") ?: JSONArray()
        for (si in 0 until streams.length()) {
            val stream = streams.optJSONObject(si) ?: continue
            val protocolName = stream.optString("protocol_name")
            val formats = stream.optJSONArray("format") ?: continue
            for (fi in 0 until formats.length()) {
                val format = formats.optJSONObject(fi) ?: continue
                val formatName = format.optString("format_name")
                val codecs = format.optJSONArray("codec") ?: continue
                for (ci in 0 until codecs.length()) {
                    val codec = codecs.optJSONObject(ci) ?: continue
                    val acceptQn = codec.optJSONArray("accept_qn") ?: JSONArray()
                    for (ai in 0 until acceptQn.length()) {
                        val q = JsonUtil.asLong(acceptQn.opt(ai))
                        if (q > 0) accept += q
                    }
                    val baseUrl = codec.optString("base_url")
                    val urlInfo = codec.optJSONArray("url_info") ?: JSONArray()
                    val currentQn = JsonUtil.asLong(codec.opt("current_qn"))
                    for (ui in 0 until urlInfo.length()) {
                        val u = urlInfo.optJSONObject(ui) ?: continue
                        val full = joinUrl(u.optString("host"), baseUrl, u.optString("extra"))
                        if (full.isEmpty()) continue
                        playUrls += PlayUrl(
                            url = full,
                            format = formatName,
                            protocol = protocolName,
                            qn = currentQn,
                        )
                    }
                }
            }
        }
        return StreamPlayInfo(
            acceptQn = accept.sortedDescending(),
            qnDesc = qnDesc,
            currentQn = JsonUtil.asLong(playurl.opt("current_qn")),
            playUrls = playUrls,
            liveStatus = JsonUtil.asLong(data.opt("live_status")),
        )
    }

    /**
     * Playback entry: room meta + HLS variants.
     * Variant [StreamVariant.id] is the qn string (`10000`/`400`/`250`/`150`/`80`);
     * [StreamVariant.format] is always `hls`.
     */
    fun streamInfo(roomId: String, line: String = ""): StreamInfo {
        val normalized = normalizeRoomId(roomId)
        require(normalized.isNotEmpty()) { "房间号为空" }
        val init = roomInit(normalized)
        var info = StreamInfo(
            roomId = normalized,
            platform = LivePlatform.BILIBILI.id,
            title = init.title,
            nickname = init.uname,
            avatar = "",
            isLive = isLiveStatus(init.liveStatus),
            variants = emptyList(),
            lines = listOf(LineOption(id = "auto", label = "自动")),
        )
        @Suppress("UNUSED_VARIABLE")
        val unusedLine = line

        runCatching { fetchRoomInfoData(init.realId) }.getOrNull()?.let { room ->
            info = info.copy(
                title = room.title.ifEmpty { info.title },
                nickname = room.nickname.ifEmpty { info.nickname },
                avatar = room.avatar,
                isLive = isLiveStatus(room.liveStatus),
            )
        }
        if (!info.isLive) return info

        val probe = fetchPlayInfo(init.realId, qn = 0)
        var available = probe.acceptQn
        if (available.isEmpty()) {
            available = probe.qnDesc.keys.sortedDescending()
        }
        if (available.isEmpty()) return info

        val targets = preferredTargets(available)
        val urls = resolveVariantsParallel(init.realId, targets)
        val variants = mutableListOf<StreamVariant>()
        for (i in targets.indices) {
            val url = urls[i] ?: continue
            val qn = targets[i]
            val label = probe.qnDesc[qn]?.ifEmpty { null } ?: "qn$qn"
            variants += StreamVariant(
                id = qn.toString(),
                label = label,
                url = url,
                format = "hls",
                urls = listOf(url),
                appliedRate = qn.toInt(),
                unconfirmed = false,
            )
        }
        return if (variants.isEmpty()) {
            info.copy(isLive = false)
        } else {
            info.copy(variants = variants)
        }
    }

    /** Single HLS URL for a quality/line (`StreamURL`). */
    fun streamUrl(roomId: String, quality: String = "", line: String = ""): String {
        val normalized = normalizeRoomId(roomId)
        require(normalized.isNotEmpty()) { "房间号为空" }
        val init = roomInit(normalized)
        @Suppress("UNUSED_VARIABLE")
        val unusedLine = line
        val probe = fetchPlayInfo(init.realId, qn = 0)
        val available = probe.acceptQn.ifEmpty {
            probe.qnDesc.keys.sortedDescending()
        }
        val qn = qualityLabelToQn(quality, available)
            ?: throw IllegalStateException("房间 $normalized 没有可用清晰度")
        return resolveVariant(init.realId, qn)
            ?: throw IllegalStateException("取流失败：房间 $normalized 的 qn=$qn 没有可用 HLS 地址")
    }

    private fun preferredTargets(available: List<Long>): List<Long> {
        val targets = mutableListOf<Long>()
        for (qn in BilibiliConstants.PREFERRED_QN) {
            if (qn in available) targets += qn
        }
        if (targets.isNotEmpty()) return targets
        return available.take(5)
    }

    private fun resolveVariantsParallel(roomId: String, targets: List<Long>): Array<String?> {
        if (targets.isEmpty()) return emptyArray()
        val results = arrayOfNulls<String>(targets.size)
        val pool = Executors.newFixedThreadPool(
            minOf(BilibiliConstants.MAX_VARIANT_FETCH, targets.size),
        )
        try {
            val futures = mutableListOf<Future<*>>()
            for (i in targets.indices) {
                val qn = targets[i]
                futures += pool.submit {
                    results[i] = resolveVariant(roomId, qn)
                }
            }
            for (f in futures) {
                runCatching { f.get(45, TimeUnit.SECONDS) }
            }
        } finally {
            pool.shutdownNow()
        }
        return results
    }

    private fun resolveVariant(roomId: String, qn: Long): String? {
        val info = runCatching { fetchPlayInfo(roomId, qn) }.getOrNull() ?: return null
        var current = info.playUrls.filter {
            it.qn == qn || it.qn == 0L || info.currentQn == qn
        }
        if (current.isEmpty()) current = info.playUrls
        return pickHlsOnly(current)?.url
    }

    /**
     * HLS-only picker. FLV hosts 404 without a Referer proxy; `.m3u8` works
     * without Referer and is what Media3 [HlsMediaSource] expects.
     */
    private fun pickHlsOnly(urls: List<PlayUrl>): PlayUrl? {
        var best: PlayUrl? = null
        var bestScore = -1
        for (u in urls) {
            if (!isHls(u)) continue
            var score = 100
            if (u.url.contains("d1--cn")) score += 20
            if (u.url.contains("mcdn")) score -= 5
            if (score > bestScore) {
                best = u
                bestScore = score
            }
        }
        return best
    }

    private fun isHls(u: PlayUrl): Boolean {
        val name = u.format.lowercase()
        if (name == "hls" || name.contains("ts")) return true
        return u.url.contains(".m3u8")
    }

    // ---- Credentials: w_webid / WBI ----------------------------------------

    private fun wWebIdValue(force: Boolean): String {
        synchronized(stateLock) {
            if (
                !force &&
                wWebId.isNotEmpty() &&
                System.currentTimeMillis() - wWebIdAtMs < BilibiliConstants.W_WEBID_TTL_MS
            ) {
                return wWebId
            }
        }
        val stale = synchronized(stateLock) { wWebId }
        var lastErr: Exception? = null
        for (page in BilibiliConstants.W_WEBID_PAGES) {
            try {
                val html = getText(page)
                val id = extractAccessId(html)
                if (id.isNotEmpty()) {
                    synchronized(stateLock) {
                        wWebId = id
                        wWebIdAtMs = System.currentTimeMillis()
                    }
                    return id
                }
                lastErr = IllegalStateException("$page 页面里没有 access_id")
            } catch (e: Exception) {
                lastErr = e
            }
        }
        // JWT ttl ~24h — slightly stale value still works briefly.
        if (stale.isNotEmpty()) return stale
        throw IllegalStateException(
            "获取 w_webid 失败: ${lastErr?.message ?: "页面里没找到 access_id"}",
        )
    }

    private fun wbiKeys(): Pair<String, String> {
        synchronized(stateLock) {
            if (
                wbiImg.isNotEmpty() &&
                System.currentTimeMillis() - wbiAtMs < BilibiliConstants.WBI_KEY_TTL_MS
            ) {
                wbiErr?.let { throw it }
                return wbiImg to wbiSub
            }
        }
        synchronized(wbiFetchLock) {
            synchronized(stateLock) {
                if (
                    wbiImg.isNotEmpty() &&
                    System.currentTimeMillis() - wbiAtMs < BilibiliConstants.WBI_KEY_TTL_MS
                ) {
                    wbiErr?.let { throw it }
                    return wbiImg to wbiSub
                }
            }
            try {
                val payload = getJson(BilibiliConstants.URL_WBI_NAV)
                val wbi = payload.optJSONObject("data")?.optJSONObject("wbi_img")
                    ?: throw IllegalStateException("nav 未返回 wbi_img")
                val img = BilibiliSign.fileKey(wbi.optString("img_url"))
                val sub = BilibiliSign.fileKey(wbi.optString("sub_url"))
                if (img.isEmpty() || sub.isEmpty()) {
                    throw IllegalStateException("nav 未返回 wbi_img")
                }
                synchronized(stateLock) {
                    wbiImg = img
                    wbiSub = sub
                    wbiAtMs = System.currentTimeMillis()
                    wbiErr = null
                }
                return img to sub
            } catch (e: Exception) {
                synchronized(stateLock) { wbiErr = e }
                throw e
            }
        }
    }

    // ---- HTTP helpers -----------------------------------------------------

    private fun headers(): Map<String, String> {
        var cookieHeader = cookie()
        if (cookieHeader.isEmpty()) {
            cookieHeader = "buvid3=i;"
        } else if (!cookieHeader.contains("buvid3=")) {
            // Device fingerprint for risk control; missing → some APIs 412.
            if (!cookieHeader.endsWith(";")) cookieHeader += ";"
            cookieHeader += " buvid3=i;"
        }
        return mapOf(
            "User-Agent" to BilibiliConstants.DESKTOP_UA,
            "Referer" to BilibiliConstants.REFERER,
            "Origin" to BilibiliConstants.ORIGIN,
            "Cookie" to cookieHeader,
        )
    }

    private fun qrLoginHeaders(): Map<String, String> = mapOf(
        "User-Agent" to BilibiliConstants.DESKTOP_UA,
        "Referer" to BilibiliConstants.WWW_REFERER,
        "Origin" to BilibiliConstants.WWW_ORIGIN,
    )

    private fun getJson(url: String): JSONObject = http.getJson(url, headers())

    private fun getText(url: String): String {
        http.get(url, headers()).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${body.take(200)}")
            }
            return body
        }
    }

    companion object {
        /** True when Cookie has both SESSDATA and bili_jct (login-ready). */
        fun cookieHasLogin(raw: String): Boolean {
            var hasSess = false
            var hasJct = false
            for (part in raw.split(';')) {
                val name = part.trim().substringBefore('=').trim()
                when (name) {
                    "SESSDATA" -> hasSess = true
                    "bili_jct" -> hasJct = true
                }
            }
            return hasSess && hasJct
        }

        fun cookieValue(raw: String, name: String): String {
            for (part in raw.split(';')) {
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                if (trimmed.substring(0, eq).trim() == name) {
                    return trimmed.substring(eq + 1).trim()
                }
            }
            return ""
        }

        /**
         * Merge `Set-Cookie` headers into [dst] (first wins). Skips cookies whose
         * Domain is set and does not contain bilibili.com.
         */
        fun mergeSetCookies(dst: MutableMap<String, String>, setCookieHeaders: List<String>) {
            for (header in setCookieHeaders) {
                val segments = header.split(';')
                if (segments.isEmpty()) continue
                val nv = segments[0]
                val eq = nv.indexOf('=')
                if (eq <= 0) continue
                val name = nv.substring(0, eq).trim()
                val value = nv.substring(eq + 1).trim()
                if (name.isEmpty()) continue
                var domain = ""
                for (i in 1 until segments.size) {
                    val attr = segments[i].trim()
                    val attrEq = attr.indexOf('=')
                    if (attrEq <= 0) continue
                    if (attr.substring(0, attrEq).trim().equals("Domain", ignoreCase = true)) {
                        domain = attr.substring(attrEq + 1).trim()
                    }
                }
                if (domain.isNotEmpty() && !domain.contains("bilibili.com")) continue
                if (name !in dst) dst[name] = value
            }
        }

        /** Pull Cookie fields from the login-success callback URL query. */
        fun mergeCookieQuery(dst: MutableMap<String, String>, rawUrl: String) {
            if (rawUrl.isBlank()) return
            try {
                val uri = URI(rawUrl)
                val query = uri.rawQuery ?: return
                for (pair in query.split('&')) {
                    if (pair.isEmpty()) continue
                    val eq = pair.indexOf('=')
                    val rawName = if (eq < 0) pair else pair.substring(0, eq)
                    val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
                    if (rawName.isEmpty() || rawValue.isEmpty()) continue
                    val name = URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
                    val value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
                    if (name !in dst) dst[name] = value
                }
            } catch (_: Exception) {
                // Malformed callback URL — ignore (matches Go).
            }
        }

        /** Fixed-order Cookie header; leftover keys sorted for stability. */
        fun buildCookieString(cookies: Map<String, String>): String {
            if (cookies.isEmpty()) return ""
            val parts = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            for (name in BilibiliConstants.COOKIE_KEY_ORDER) {
                val v = cookies[name]
                if (!v.isNullOrEmpty()) {
                    parts += "$name=$v"
                    seen += name
                }
            }
            val rest = mutableListOf<String>()
            for ((name, v) in cookies) {
                if (name in seen || v.isEmpty()) continue
                rest += "$name=$v"
            }
            rest.sort()
            parts += rest
            return parts.joinToString("; ")
        }

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        private val areaCacheLock = Any()
        @Volatile private var areaCache: List<CategoryGroup>? = null
        @Volatile private var areaCachedAtMs: Long = 0L

        /** live_status==1 only; 2=轮播 treated as offline (matches desktop follows). */
        fun isLiveStatus(liveStatus: Long): Boolean = liveStatus == 1L

        fun normalizeRoomId(raw: String): String {
            var text = raw.trim()
            if (text.isEmpty()) return ""
            if (text.contains("//")) {
                val idx = text.indexOf("//")
                if (idx >= 0) text = text.substring(idx + 2)
            }
            val cut = text.indexOfAny(charArrayOf('?', '#'))
            if (cut >= 0) text = text.substring(0, cut)
            text = text.trimEnd('/')
            val slash = text.lastIndexOf('/')
            if (slash >= 0) text = text.substring(slash + 1)
            val digits = StringBuilder()
            for (r in text) {
                if (r in '0'..'9') {
                    digits.append(r)
                    continue
                }
                if (digits.isNotEmpty()) break
            }
            return digits.toString()
        }

        fun splitAreaId(raw: String): Pair<String, String> {
            var text = raw.trim()
            text = text.removePrefix("/category/").trimEnd('/')
            require(text.isNotEmpty()) { "分区 id 为空" }
            val idx = text.lastIndexOf('_')
            return if (idx > 0 && idx < text.length - 1) {
                text.substring(0, idx) to text.substring(idx + 1)
            } else {
                "" to text
            }
        }

        fun extractAccessId(html: String): String {
            for (needle in listOf("\"access_id\":\"", "\"access_id\": \"")) {
                val idx = html.indexOf(needle)
                if (idx < 0) continue
                val rest = html.substring(idx + needle.length)
                val end = rest.indexOf('"')
                if (end > 0) return rest.substring(0, end)
            }
            return ""
        }

        fun joinUrl(host: String, base: String, extra: String): String {
            if (host.isEmpty() || base.isEmpty()) return ""
            var full = host.trimEnd('/') + base
            if (extra.isNotEmpty()) {
                var ex = extra
                if (!ex.startsWith("?") && !base.contains("?")) {
                    full += "?"
                } else if (ex.startsWith("?") && base.contains("?")) {
                    ex = ex.removePrefix("?")
                    full += "&"
                }
                full += ex
            }
            return enforceHttps(full)
        }

        fun enforceHttps(raw: String): String =
            if (raw.startsWith("http://")) "https://" + raw.removePrefix("http://") else raw

        fun httpsUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            if (trimmed.startsWith("//")) return "https:$trimmed"
            return enforceHttps(trimmed)
        }

        fun firstNonEmpty(vararg values: String): String =
            values.firstOrNull { it.isNotBlank() }.orEmpty()

        fun stripEmTags(text: String): String {
            if (!text.contains("<em")) return text
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                if (text[i] == '<') {
                    val end = text.indexOf('>', i)
                    if (end >= 0) {
                        i = end + 1
                        continue
                    }
                }
                out.append(text[i])
                i++
            }
            return out.toString()
        }

        fun jsonBool(value: Any?): Boolean =
            when (value) {
                null, JSONObject.NULL -> false
                is Boolean -> value
                is Number -> value.toDouble() != 0.0
                is String -> value == "true" || value == "1"
                else -> false
            }

        /**
         * Map UI quality (qn string or Chinese label) → available qn.
         * [available] must be descending.
         */
        fun qualityLabelToQn(quality: String, available: List<Long>): Long? {
            if (available.isEmpty()) return null
            val text = quality.trim()
            if (text.isEmpty()) return available.first()
            text.toLongOrNull()?.let { n ->
                return nearestAtOrBelow(available, n) ?: available.first()
            }
            val target = when (text.lowercase()) {
                "原画", "蓝光原画", "source", "origin" -> BilibiliConstants.QN_ORIGIN
                "蓝光", "bluray" -> BilibiliConstants.QN_BLURAY
                "超清", "high" -> BilibiliConstants.QN_ULTRA
                "高清", "standard" -> BilibiliConstants.QN_HIGH
                "标清", "流畅", "low", "smooth" -> BilibiliConstants.QN_SMOOTH
                "4k", "2k", "杜比", "dolby" -> BilibiliConstants.QN_ORIGIN
                else -> return available.first()
            }
            return nearestAtOrBelow(available, target) ?: available.last()
        }

        fun nearestAtOrBelow(available: List<Long>, target: Long): Long? {
            for (qn in available) {
                if (qn <= target) return qn
            }
            return null
        }
    }
}

/** Bilibili business error (HTTP 200 + code != 0). */
class BilibiliApiException(
    val code: Long,
    val apiMessage: String,
    val endpoint: String,
) : IllegalStateException(
    "bilibili $endpoint 返回 code=$code (${apiMessage.ifEmpty { "-" }})",
)

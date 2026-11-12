package com.dtv.androidtv.data.douyu

import android.util.Log
import com.dtv.androidtv.data.model.AuthAccount
import com.dtv.androidtv.data.model.Category
import com.dtv.androidtv.data.model.CategoryGroup
import com.dtv.androidtv.data.model.LineOption
import com.dtv.androidtv.data.model.LivePlatform
import com.dtv.androidtv.data.model.QRLoginCode
import com.dtv.androidtv.data.model.QRLoginResult
import com.dtv.androidtv.data.model.QRLoginStatus
import com.dtv.androidtv.data.model.SearchHit
import com.dtv.androidtv.data.model.StreamInfo
import com.dtv.androidtv.data.model.StreamVariant
import com.dtv.androidtv.data.model.Streamer
import com.dtv.androidtv.data.model.StreamerPage
import com.dtv.androidtv.data.net.HttpJsonClient
import okhttp3.Cookie
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Kotlin contract-port of Go `internal/platforms/douyu.Client`.
 *
 * Categories, live list, room detail, stream resolve, QR login / cookie session.
 */
class DouyuClient(
    private val http: HttpJsonClient = HttpJsonClient(),
) {
    @Volatile
    private var did: String = generateDeviceId()

    @Volatile
    private var cookie: String = ""

    private val encLock = Any()
    private var enc: EncryptionData? = null
    private var encFetchedAtMs: Long = 0L
    private var encDid: String = ""

    // ---- Cookie session ---------------------------------------------------

    /**
     * Apply a login Cookie (empty clears session). Syncs `did` from
     * `dy_did`/`acf_did` and invalidates the encryption descriptor cache
     * (Go `SetCookie`).
     */
    fun setCookie(raw: String) {
        var trimmed = raw.trim()
        if (trimmed.startsWith("Cookie:", ignoreCase = true)) {
            trimmed = trimmed.substring(7).trim()
        }
        val fields = parseCookieFields(trimmed)
        cookie = trimmed
        val syncedDid = fields["dy_did"]?.takeIf { it.isNotBlank() }
            ?: fields["acf_did"]?.takeIf { it.isNotBlank() }
        if (syncedDid != null) {
            did = syncedDid
        }
        synchronized(encLock) {
            enc = null
            encFetchedAtMs = 0L
            encDid = ""
        }
        // Go also clears the HTTP jar; OkHttp here has no jar by default.
    }

    fun cookie(): String = cookie

    /** Local field check only — no network (Go `CookieValid` → AuthAccount). */
    fun cookieValid(): AuthAccount {
        val raw = cookie()
        if (!cookieHasLogin(raw)) {
            return AuthAccount(platform = LivePlatform.DOUYU.id, loggedIn = false)
        }
        return AuthAccount(
            platform = LivePlatform.DOUYU.id,
            loggedIn = true,
            userName = cookieUserName(raw),
        )
    }

    /** Convenience for callers that only need a boolean (e.g. restoreAuth). */
    fun isLoggedIn(): Boolean = cookieHasLogin(cookie())

    fun loginUserName(): String = cookieUserName(cookie())

    /**
     * Passport `safeAuth` renewal. On transport/parse failure throws but leaves
     * the prior cookie in place (caller may still read [cookie]).
     */
    fun refreshCookie(): String {
        val full = fullCookie()
        if (!cookieHasLogin(full)) {
            throw IllegalStateException("斗鱼未登录")
        }
        val headers = mapOf(
            "accept" to "application/json, text/plain, */*",
            "accept-language" to "zh-CN,zh;q=0.9,en;q=0.7",
            "referer" to "https://www.douyu.com/directory/myFollow",
            "user-agent" to DouyuConstants.DESKTOP_UA,
            "x-requested-with" to "XMLHttpRequest",
            "cookie" to full,
        )
        val (body, setCookies) = try {
            http.get(DouyuConstants.URL_SAFE_AUTH, headers).use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("斗鱼 Cookie 续期失败：HTTP ${resp.code}")
                }
                resp.body?.string().orEmpty() to cookiesFromResponse(resp)
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("斗鱼 Cookie 续期失败：${e.message}", e)
        }
        val payload = try {
            JSONObject(body.ifBlank { "{}" })
        } catch (e: Exception) {
            throw IllegalStateException("斗鱼 Cookie 续期解析失败：${e.message}", e)
        }
        if (payload.optInt("error") != 0) {
            throw IllegalStateException(
                "斗鱼 Cookie 续期失败：error=${payload.optInt("error")} (${payload.optString("msg")})",
            )
        }
        val fields = parseCookieFields(cookie()).toMutableMap()
        mergeCookieList(fields, setCookies, override = true)
        if (fields["dy_did"].isNullOrBlank()) fields["dy_did"] = did
        if (fields["acf_did"].isNullOrBlank()) fields["acf_did"] = did
        val renewed = buildCookieString(fields, LOGIN_COOKIE_ORDER)
        if (cookieHasLogin(renewed)) {
            setCookie(renewed)
            return renewed
        }
        throw IllegalStateException("斗鱼 Cookie 续期后登录字段丢失")
    }

    fun clearCookie() = setCookie("")

    fun logout() = clearCookie()

    // ---- QR login ---------------------------------------------------------

    /** Request a Douyu login QR payload (Go `QRLoginGenerate`). */
    fun qrLoginGenerate(): QRLoginCode {
        val form = mapOf(
            "client_id" to "1",
            "isMultiAccount" to "0",
        )
        http.postForm(DouyuConstants.URL_QR_GENERATE, form, qrHeaders()).use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("申请斗鱼登录二维码失败：HTTP ${resp.code}")
            }
            val payload = JSONObject(body.ifBlank { "{}" })
            if (payload.optInt("error") != 0) {
                throw IllegalStateException(
                    "申请斗鱼登录二维码失败：error=${payload.optInt("error")} (${payload.optString("msg")})",
                )
            }
            val data = payload.optJSONObject("data")
                ?: throw IllegalStateException("斗鱼未返回有效的二维码内容")
            val code = data.optString("code").trim()
            val url = data.optString("url").trim()
            if (code.isEmpty() || url.isEmpty()) {
                throw IllegalStateException("斗鱼未返回有效的二维码内容")
            }
            var expire = data.optInt("expire")
            if (expire <= 0) expire = 300
            return QRLoginCode(url = url, key = code, expiresIn = expire)
        }
    }

    /** One poll of scan status; on confirm, finishes login callback (Go `QRLoginPoll`). */
    fun qrLoginPoll(key: String): QRLoginResult {
        val code = key.trim()
        if (code.isEmpty()) throw IllegalArgumentException("缺少扫码 code")
        val target =
            "${DouyuConstants.URL_QR_CHECK}?time=${System.currentTimeMillis()}&code=" +
                URLEncoder.encode(code, Charsets.UTF_8.name())
        http.get(target, qrHeaders()).use { resp ->
            val pollCookies = cookiesFromResponse(resp)
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("查询斗鱼扫码状态失败：HTTP ${resp.code}")
            }
            val payload = JSONObject(body.ifBlank { "{}" })
            val message = stringFromQrData(payload.opt("data"))
            return when (payload.optInt("error")) {
                0 -> {
                    val data = payload.optJSONObject("data")
                    val loginPath = data?.optString("url").orEmpty().trim()
                    if (data == null) {
                        QRLoginResult(
                            status = QRLoginStatus.ERROR,
                            message = "斗鱼登录返回了无法识别的数据",
                        )
                    } else if (loginPath.isEmpty()) {
                        QRLoginResult(
                            status = QRLoginStatus.ERROR,
                            message = "斗鱼登录未返回回调地址",
                        )
                    } else {
                        finishLogin(loginPath, pollCookies)
                    }
                }
                1 -> QRLoginResult(
                    status = QRLoginStatus.SCANNED,
                    message = firstNonEmpty(message, payload.optString("msg"), "已扫码，请在手机上确认登录"),
                )
                -2 -> QRLoginResult(
                    status = QRLoginStatus.PENDING,
                    message = firstNonEmpty(message, payload.optString("msg"), "等待手机扫码"),
                )
                -1 -> QRLoginResult(
                    status = QRLoginStatus.EXPIRED,
                    message = firstNonEmpty(message, payload.optString("msg"), "二维码已失效，请刷新"),
                )
                9993, 9995, 9997, 9999 -> QRLoginResult(
                    status = QRLoginStatus.ERROR,
                    message = firstNonEmpty(message, "斗鱼扫码登录失败"),
                )
                else -> QRLoginResult(
                    status = QRLoginStatus.PENDING,
                    message = firstNonEmpty(message, payload.optString("msg"), "等待手机扫码"),
                )
            }
        }
    }

    private fun finishLogin(rawUrl: String, pollCookies: List<Cookie>): QRLoginResult {
        val loginUrl = buildLoginCallbackUrl(rawUrl, System.currentTimeMillis())
        http.get(loginUrl, qrHeaders()).use { resp ->
            val setCookies = cookiesFromResponse(resp)
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("斗鱼登录回调失败：HTTP ${resp.code}")
            }
            val payload = parseJsonpObject(body)
            if (payload.optInt("error") != 0) {
                return QRLoginResult(
                    status = QRLoginStatus.ERROR,
                    message = firstNonEmpty(
                        stringFromQrData(payload.opt("msg")),
                        "斗鱼登录失败",
                    ),
                )
            }
            val fields = linkedMapOf<String, String>()
            mergeCookieList(fields, pollCookies, override = false)
            mergeCookieList(fields, setCookies, override = true)
            // Go also merges jar cookies for www / passport; no jar here.
            if (fields["dy_did"].isNullOrBlank()) fields["dy_did"] = did
            if (fields["acf_did"].isNullOrBlank()) fields["acf_did"] = did
            val cookieStr = buildCookieString(fields, LOGIN_COOKIE_ORDER)
            if (!cookieHasLogin(cookieStr)) {
                return QRLoginResult(
                    status = QRLoginStatus.ERROR,
                    message = "斗鱼登录成功但未拿到有效 Cookie，请重试",
                )
            }
            setCookie(cookieStr)
            var result = QRLoginResult(
                status = QRLoginStatus.SUCCESS,
                message = "登录成功",
                cookie = cookieStr,
            )
            val data = payload.optJSONObject("data")
            if (data != null) {
                result = result.copy(
                    userName = firstNonEmpty(
                        stringFromQrData(data.opt("nickname")),
                        stringFromQrData(data.opt("nickName")),
                        stringFromQrData(data.opt("nick_name")),
                        stringFromQrData(data.opt("username")),
                        stringFromQrData(data.opt("userName")),
                    ),
                    avatar = firstNonEmpty(
                        stringFromQrData(data.opt("avatar")),
                        stringFromQrData(data.opt("avatar_url")),
                        stringFromQrData(data.opt("avatarUrl")),
                    ),
                )
            }
            if (result.userName.isEmpty()) {
                result = result.copy(userName = cookieUserName(cookieStr))
            }
            try {
                val refreshed = refreshCookie()
                if (refreshed.isNotEmpty()) {
                    result = result.copy(cookie = refreshed)
                }
            } catch (e: Exception) {
                Log.w(TAG, "登录后校验失败（仍按成功处理）: ${e.message}")
            }
            return result
        }
    }

    private fun qrHeaders(): Map<String, String> {
        val authDid = did
        return mapOf(
            "accept" to "application/json, text/plain, */*",
            "accept-language" to "zh-CN,zh;q=0.9,en;q=0.7",
            "origin" to "https://passport.douyu.com",
            "referer" to DouyuConstants.QR_LOGIN_REFERER,
            "user-agent" to DouyuConstants.DESKTOP_UA,
            "cookie" to "dy_did=$authDid; acf_did=$authDid",
        )
    }

    // ---- Categories -------------------------------------------------------

    /** Fetch cate1 → cate2 tree (`FetchCategories`). */
    fun fetchCategories(): List<CategoryGroup> {
        val payload = http.getJson(
            DouyuConstants.URL_CATE_LIST,
            mapOf(
                "User-Agent" to DouyuConstants.MOBILE_UA,
                "Referer" to "https://m.douyu.com/",
            ),
        )
        if (payload.optInt("code") != 0) {
            throw IllegalStateException(
                "斗鱼分区接口返回错误 code=${payload.optInt("code")} msg=${payload.optString("msg")}",
            )
        }
        val data = payload.optJSONObject("data")
            ?: throw IllegalStateException("斗鱼分区接口未返回 data")
        val groups = buildCategoryGroups(
            data.optJSONArray("cate1Info") ?: JSONArray(),
            data.optJSONArray("cate2Info") ?: JSONArray(),
        )
        if (groups.isEmpty()) {
            throw IllegalStateException("斗鱼分区接口返回空列表")
        }
        return groups
    }

    // ---- Live list --------------------------------------------------------

    /** Page of rooms for a cate2 id (`FetchLiveList`). */
    fun fetchLiveList(
        cate2Id: String,
        page: Int = 1,
        limit: Int = DouyuConstants.DEFAULT_PAGE_SIZE,
    ): StreamerPage {
        val id = cate2Id.trim().ifEmpty {
            throw IllegalArgumentException("缺少二级分区 id")
        }
        val safePage = max(1, page)
        val safeLimit = if (limit <= 0) DouyuConstants.DEFAULT_PAGE_SIZE else limit
        val target = DouyuConstants.URL_DIRECTORY.format("2_$id", safePage, safeLimit)
        return fetchDirectory(target, safePage, safeLimit)
    }

    private fun fetchDirectory(target: String, page: Int, limit: Int): StreamerPage {
        val payload = http.getJson(
            target,
            mapOf(
                "User-Agent" to DouyuConstants.DESKTOP_UA,
                "Referer" to "https://www.douyu.com/",
            ),
        )
        if (payload.optInt("code") != 0) {
            throw IllegalStateException(
                "斗鱼列表接口返回错误 code=${payload.optInt("code")} msg=${payload.optString("msg")}",
            )
        }
        val data = payload.optJSONObject("data") ?: JSONObject()
        val rl = data.optJSONArray("rl") ?: JSONArray()
        val rooms = buildList {
            for (i in 0 until rl.length()) {
                mapDirectoryItem(rl.optJSONObject(i))?.let { add(it) }
            }
        }.let { if (it.size > limit) it.take(limit) else it }

        val totalPages = data.optInt("pgcnt", 0)
        return StreamerPage(
            items = rooms,
            page = page,
            hasMore = totalPages > page,
            totalPages = totalPages,
        )
    }

    private fun mapDirectoryItem(raw: JSONObject?): Streamer? {
        if (raw == null) return null
        val roomId = JsonUtil.asString(raw.opt("rid"))
        if (roomId.isEmpty()) return null
        var cover = JsonUtil.fullUrl(DouyuConstants.COVER_PREFIX, JsonUtil.asString(raw.opt("rs16")))
        if (cover.isEmpty()) {
            cover = JsonUtil.fullUrl(DouyuConstants.COVER_PREFIX, JsonUtil.asString(raw.opt("rs1")))
        }
        var nickname = JsonUtil.asString(raw.opt("nn"))
        if (nickname.isEmpty()) nickname = "房间 $roomId"
        var isLive = true
        if (raw.has("type") && !raw.isNull("type")) {
            isLive = JsonUtil.asLong(raw.opt("type")) == 1L
        }
        return Streamer(
            id = roomId,
            platform = LivePlatform.DOUYU.id,
            nickname = nickname,
            title = JsonUtil.asString(raw.opt("rn")),
            cover = cover,
            avatar = douyuAvatarUrl(JsonUtil.asString(raw.opt("av"))),
            viewers = JsonUtil.asDouble(raw.opt("ol")).toLong(),
            categoryName = JsonUtil.asString(raw.opt("c2name")),
            isLive = isLive,
        )
    }

    // ---- Room detail ------------------------------------------------------

    data class RoomDetail(
        val realRoomId: String,
        val title: String,
        val nickname: String,
        val avatar: String,
        val cover: String,
        val category: String,
        val isLive: Boolean,
    )

    /** Betard room detail (`FetchRoomDetail`). */
    fun fetchRoomDetail(roomId: String): RoomDetail {
        require(roomId.isNotBlank()) { "缺少房间号" }
        val payload = http.getJson(
            DouyuConstants.URL_BETARD.format(roomId),
            mapOf(
                "User-Agent" to DouyuConstants.DESKTOP_UA,
                "Referer" to refererFor(roomId),
            ),
        )
        val room = locateRoomObject(payload)
            ?: throw IllegalStateException("房间 $roomId 未返回房间数据（可能房间不存在）")
        var realId = JsonUtil.asString(room.opt("room_id")).ifEmpty { roomId }
        var nickname = JsonUtil.asString(room.opt("nickname"))
        if (nickname.isEmpty()) {
            nickname = firstNonEmpty(
                JsonUtil.asString(room.opt("owner_name")),
                "房间 $roomId",
            )
        }
        val avatar = firstNonEmpty(
            JsonUtil.asString(room.opt("avatar_mid")),
            nestedString(room, "avatar", "middle"),
        )
        val showStatus = JsonUtil.asLong(room.opt("show_status"))
        val videoLoop = JsonUtil.asLong(room.opt("videoLoop"))
        val isLoop = JsonUtil.asLong(room.opt("isLoop"))
        return RoomDetail(
            realRoomId = realId,
            title = JsonUtil.asString(room.opt("room_name")),
            nickname = nickname,
            avatar = avatar,
            cover = JsonUtil.fullUrl(DouyuConstants.COVER_PREFIX, JsonUtil.asString(room.opt("room_src"))),
            category = firstNonEmpty(
                JsonUtil.asString(room.opt("second_lvl_name")),
                JsonUtil.asString(room.opt("cate2_name")),
            ),
            isLive = isLiveFromFlags(showStatus, isLoop, videoLoop),
        )
    }

    /**
     * Keyword search for anchors (`SearchAnchor`).
     * type===1 only; isLive: 1=live, 2=offline (normalized).
     */
    fun searchAnchor(keyword: String, page: Int = 1, pageSize: Int = 20): List<SearchHit> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return emptyList()
        val safePage = max(1, page)
        val safeSize = if (pageSize <= 0) 20 else pageSize
        val did = MessageDigest.getInstance("MD5")
            .digest(System.nanoTime().toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = DouyuConstants.URL_SEARCH_USER +
            "?kw=${URLEncoder.encode(trimmed, "UTF-8")}" +
            "&page=$safePage&pageSize=$safeSize&filterType=0"
        val payload = http.getJson(
            target,
            mapOf(
                "User-Agent" to DouyuConstants.DESKTOP_UA,
                "Referer" to "https://www.douyu.com/search/",
                "Cookie" to "dy_did=$did; acf_did=$did",
            ),
        )
        val data = payload.optJSONObject("data") ?: JSONObject()
        var items = data.optJSONArray("relateUser")
        if (items == null || items.length() == 0) {
            items = data.optJSONArray("relateUserList") ?: JSONArray()
        }
        val out = mutableListOf<SearchHit>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            // Go: skip when type is present and not 1 (anchor).
            if (item.has("type") && JsonUtil.asLong(item.opt("type")) != 1L) continue
            val info = item.optJSONObject("anchorInfo") ?: continue
            var roomId = JsonUtil.asString(info.opt("rid"))
            if (roomId.isEmpty()) {
                val bk = JsonUtil.asString(info.opt("bkUrl"))
                if (bk.isNotEmpty()) roomId = bk.trimEnd('/').substringAfterLast('/')
            }
            val nickname = firstNonEmpty(
                JsonUtil.asString(info.opt("nickName")),
                JsonUtil.asString(info.opt("nickname")),
            )
            if (roomId.isEmpty() || nickname.isEmpty()) continue
            val title = firstNonEmpty(
                JsonUtil.asString(info.opt("roomName")),
                JsonUtil.asString(info.opt("description")),
                "暂无标题",
            )
            val liveRaw = JsonUtil.asLong(info.opt("isLive"))
            out += SearchHit(
                id = roomId,
                nickname = nickname,
                title = title,
                avatar = JsonUtil.fullUrl(
                    DouyuConstants.AVATAR_PREFIX,
                    JsonUtil.asString(info.opt("avatar")),
                ),
                isLive = liveRaw == 1L,
            )
        }
        return out
    }

    /** Light room card for follow/recent refresh (`FetchRoomInfo`). */
    fun fetchRoomInfo(roomId: String): Streamer {
        val detail = fetchRoomDetail(roomId)
        return Streamer(
            id = detail.realRoomId,
            platform = LivePlatform.DOUYU.id,
            nickname = detail.nickname,
            title = detail.title,
            cover = detail.cover,
            avatar = detail.avatar,
            viewers = 0L,
            categoryName = detail.category,
            isLive = detail.isLive,
        )
    }

    // ---- Stream resolve ---------------------------------------------------

    /**
     * Playback entry: room meta + all rates/lines (`StreamInfo`).
     *
     * Douyu getH5PlayV1 returns FLV progressive URLs (format="flv").
     * Prefer `.m3u8` if ever present; apply expire=0 patch for ws CDN 300s links.
     */
    fun streamInfo(roomId: String, line: String = ""): StreamInfo {
        val detail = fetchRoomDetail(roomId)
        val info = StreamInfo(
            roomId = detail.realRoomId,
            platform = LivePlatform.DOUYU.id,
            title = detail.title,
            nickname = detail.nickname,
            avatar = detail.avatar,
            isLive = detail.isLive,
            variants = emptyList(),
            lines = emptyList(),
        )
        if (!detail.isLive) return info

        val (rates, cdns) = playQualities(detail.realRoomId)
        val resolveCdns = orderCdnsForLine(cdns, line)
        val variants = mutableListOf<StreamVariant>()
        var firstErr: Exception? = null
        for (rate in rates) {
            try {
                val (urls, applied, unconfirmed) = resolveQualityUrls(detail.realRoomId, rate, resolveCdns)
                if (urls.isEmpty()) continue
                variants += StreamVariant(
                    id = rate.rate.toString(),
                    label = rateLabel(rate),
                    url = urls.first(),
                    format = detectFormat(urls.first()),
                    urls = urls,
                    appliedRate = applied,
                    unconfirmed = unconfirmed,
                )
            } catch (e: Exception) {
                if (firstErr == null) firstErr = e
            }
        }
        if (variants.isEmpty()) {
            throw firstErr ?: IllegalStateException("房间 ${detail.realRoomId} 没有可用的直播流")
        }
        return info.copy(variants = variants, lines = cdns)
    }

    /**
     * Single URL for a quality/line — used by FLV re-sign / renew path (`StreamURL`).
     */
    fun streamUrl(roomId: String, quality: String = "", line: String = ""): String {
        val detail = fetchRoomDetail(roomId)
        if (!detail.isLive) throw IllegalStateException("主播未开播")
        val (rates, cdns) = playQualities(detail.realRoomId)
        val rate = resolveRate(quality, rates) ?: rates.first()
        val cdn = selectCdn(line, cdns)
        val resolution = resolvePlayUrl(detail.realRoomId, rate.rate, cdn)
        return resolution.urls.firstOrNull()
            ?: throw IllegalStateException("斗鱼没有返回可用的直播流")
    }

    // ---- Encryption / play ------------------------------------------------

    private data class EncryptionData(
        val did: String,
        val key: String,
        val randStr: String,
        val encTime: Int,
        val encData: String,
        val isSpecial: Int,
        val expiryAt: Long,
    )

    private data class RateVariant(val name: String, val rate: Int, val bit: Int = 0)

    private data class PlayUrlResolution(
        val urls: List<String>,
        val appliedRate: Int?,
        val unconfirmed: Boolean,
    )

    private fun ensureEncryption(force: Boolean): EncryptionData {
        synchronized(encLock) {
            val now = System.currentTimeMillis()
            val cached = enc
            if (
                !force &&
                cached != null &&
                encDid == did &&
                now - encFetchedAtMs < TimeUnit.MINUTES.toMillis(5) &&
                cached.expiryAt > TimeUnit.MILLISECONDS.toSeconds(now) + 30 &&
                cached.encTime in 1..16 &&
                cached.key.isNotEmpty() &&
                cached.randStr.isNotEmpty() &&
                cached.encData.isNotEmpty()
            ) {
                return cached
            }
            val payload = http.getJson(
                DouyuConstants.URL_GET_ENCRYPTION.format(did),
                requestHeadersForDid("", did),
            )
            if (payload.optInt("error") != 0) {
                throw IllegalStateException(
                    "斗鱼签名接口返回 error=${payload.optInt("error")} msg=${payload.optString("msg")}",
                )
            }
            val d = payload.optJSONObject("data")
                ?: throw IllegalStateException("斗鱼签名接口未返回有效参数")
            val key = d.optString("key")
            val randStr = d.optString("rand_str")
            val encData = d.optString("enc_data")
            val encTime = d.optInt("enc_time")
            val expireAt = d.optLong("expire_at")
            if (key.isEmpty() || randStr.isEmpty() || encData.isEmpty()) {
                throw IllegalStateException("斗鱼签名接口未返回有效参数")
            }
            if (encTime !in 1..16) {
                throw IllegalStateException("斗鱼签名接口 enc_time 非法：$encTime")
            }
            if (expireAt <= TimeUnit.MILLISECONDS.toSeconds(now) + 30) {
                throw IllegalStateException("斗鱼签名描述符已过期")
            }
            val fresh = EncryptionData(
                did = did,
                key = key,
                randStr = randStr,
                encTime = encTime,
                encData = encData,
                isSpecial = d.optInt("is_special"),
                expiryAt = expireAt,
            )
            enc = fresh
            encFetchedAtMs = now
            encDid = did
            return fresh
        }
    }

    private fun sign(roomId: String, rate: Int, cdn: String, force: Boolean): Pair<Map<String, String>, String> {
        val encData = ensureEncryption(force)
        val ts = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        val suffix = if (encData.isSpecial != 1) roomId + ts else ""
        var secret = encData.randStr
        repeat(encData.encTime) {
            secret = md5Hex(secret + encData.key)
        }
        val form = linkedMapOf(
            "enc_data" to encData.encData,
            "tt" to ts.toString(),
            "did" to encData.did,
            "auth" to md5Hex(secret + encData.key + suffix),
            "cdn" to cdn,
            "rate" to rate.toString(),
            "hevc" to "0",
            "fa" to "0",
            "ive" to "0",
            "ver" to "Douyu_new",
            "iar" to "0",
        )
        return form to encData.did
    }

    private fun requestPlayData(roomId: String, rate: Int, cdn: String): JSONObject {
        require(roomId.isNotBlank()) { "缺少房间号" }
        var lastErr: Exception? = null
        repeat(2) { attempt ->
            try {
                val (form, requestDid) = sign(roomId, rate, cdn, force = attempt > 0)
                val payload = http.postFormJson(
                    DouyuConstants.URL_GET_H5_PLAY.format(roomId),
                    form,
                    requestHeadersForDid(roomId, requestDid),
                )
                var code = payload.optInt("error")
                if (code == 0) code = payload.optInt("code")
                if (code != 0) {
                    lastErr = IllegalStateException(
                        "斗鱼取流接口返回 error=$code msg=${payload.optString("msg")}（rate=$rate cdn=$cdn）",
                    )
                    return@repeat
                }
                if (payload.optJSONObject("data") == null) {
                    lastErr = IllegalStateException("斗鱼取流接口未返回 data（rate=$rate cdn=$cdn）")
                    return@repeat
                }
                return payload
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: IllegalStateException("斗鱼取流失败")
    }

    private fun playQualities(roomId: String): Pair<List<RateVariant>, List<LineOption>> {
        val payload = requestPlayData(roomId, rate = -1, cdn = "")
        val data = payload.getJSONObject("data")
        return parsePlayQualities(data) to parseCdns(data)
    }

    private fun parsePlayQualities(data: JSONObject): List<RateVariant> {
        val rates = mutableListOf<RateVariant>()
        val seen = mutableSetOf<Int>()
        val multirates = data.optJSONArray("multirates") ?: JSONArray()
        for (i in 0 until multirates.length()) {
            val item = multirates.optJSONObject(i) ?: continue
            val rate = JsonUtil.asInt(item.opt("rate")) ?: continue
            if (!seen.add(rate)) continue
            rates += RateVariant(
                name = item.optString("name").trim(),
                rate = rate,
                bit = JsonUtil.asLong(item.opt("bit")).toInt(),
            )
        }
        if (rates.isEmpty()) {
            val rate = JsonUtil.asInt(data.opt("rate")) ?: -1
            rates += RateVariant(name = "默认", rate = rate)
        }
        return rates
    }

    private fun parseCdns(data: JSONObject): List<LineOption> {
        val result = mutableListOf<LineOption>()
        val seen = mutableSetOf<String>()
        fun add(cdnRaw: String, labelRaw: String) {
            val cdn = cdnRaw.trim()
            if (cdn.isEmpty() || !seen.add(cdn)) return
            val label = labelRaw.trim().ifEmpty { cdn }
            result += LineOption(id = cdn, label = label)
        }
        val cdns = data.optJSONArray("cdnsWithName") ?: JSONArray()
        for (i in 0 until cdns.length()) {
            val item = cdns.optJSONObject(i) ?: continue
            add(item.optString("cdn"), item.optString("name"))
        }
        val current = data.optString("rtmp_cdn").trim()
        if (current.isNotEmpty() && current !in seen) {
            result.add(0, LineOption(id = current, label = current))
            seen += current
        }
        if (result.isEmpty()) {
            result += LineOption(id = "", label = "默认")
        }
        return result.sortedWith(
            compareBy { it.id.lowercase().startsWith("scdn") },
        )
    }

    private fun orderCdnsForLine(cdns: List<LineOption>, line: String): List<LineOption> {
        val wanted = line.trim().lowercase()
        if (wanted.isEmpty()) return cdns
        val index = cdns.indexOfFirst {
            it.id.equals(wanted, true) || it.label.equals(wanted, true)
        }
        if (index <= 0) return cdns
        return listOf(cdns[index]) + cdns.take(index) + cdns.drop(index + 1)
    }

    private fun selectCdn(requested: String, lines: List<LineOption>): String {
        val trimmed = requested.trim().lowercase()
        if (trimmed.isNotEmpty()) {
            lines.firstOrNull {
                it.id.equals(trimmed, true) || it.label.equals(trimmed, true)
            }?.let { return it.id }
        }
        if (lines.isNotEmpty()) return lines.first().id
        if (trimmed.isNotEmpty()) return trimmed
        return DouyuConstants.DEFAULT_CDN
    }

    private fun resolvePlayUrl(roomId: String, rate: Int, cdn: String): PlayUrlResolution {
        val payload = requestPlayData(roomId, rate, cdn)
        val data = payload.getJSONObject("data")
        var playUrl = parsePlayUrl(data)
        playUrl = applyExpirePatch(playUrl)
        val applied = JsonUtil.asInt(data.opt("rate"))
        return if (applied != null && applied >= 0) {
            PlayUrlResolution(listOf(playUrl), applied, unconfirmed = false)
        } else {
            PlayUrlResolution(listOf(playUrl), null, unconfirmed = true)
        }
    }

    private fun resolveQualityUrls(
        roomId: String,
        requested: RateVariant,
        cdns: List<LineOption>,
    ): Triple<List<String>, Int?, Boolean> {
        data class Group(var rate: Int, var known: Boolean, val urls: MutableList<String>)
        val groups = mutableListOf<Group>()
        val index = mutableMapOf<Int, Int>()
        var lastErr: Exception? = null
        for (cdn in cdns) {
            try {
                val resolution = resolvePlayUrl(roomId, requested.rate, cdn.id)
                var key = -1
                var known = false
                resolution.appliedRate?.takeIf { it >= 0 }?.let {
                    key = it
                    known = true
                }
                val idx = index.getOrPut(key) {
                    groups.size.also {
                        groups += Group(rate = key, known = false, urls = mutableListOf())
                    }
                }
                if (known) {
                    groups[idx].known = true
                    groups[idx].rate = key
                }
                for (u in resolution.urls) {
                    if (u !in groups[idx].urls) groups[idx].urls += u
                }
            } catch (e: Exception) {
                lastErr = e
            }
        }
        if (groups.isEmpty()) {
            throw lastErr ?: IllegalStateException("斗鱼没有返回可用的直播流")
        }
        if (requested.rate >= 0) {
            index[requested.rate]?.let { idx ->
                return Triple(groups[idx].urls, groups[idx].rate, false)
            }
        }
        groups.firstOrNull { it.known }?.let {
            return Triple(it.urls, it.rate, false)
        }
        return Triple(groups.first().urls, null, true)
    }

    private fun parsePlayUrl(data: JSONObject): String {
        val live = unescape(data.optString("rtmp_live")).trim()
        if (isPlayableUrl(live)) return live

        val bases = listOf(
            unescape(data.optString("rtmp_url")).trim(),
            unescape(JsonUtil.asString(data.opt("flv_url"))).trim(),
        )
        for (base in bases) {
            if (base.isEmpty() || live.isEmpty()) continue
            val combined = base.trimEnd('/') + "/" + live.trimStart('/')
            if (isPlayableUrl(combined)) return combined
        }
        for (candidate in listOf(
            JsonUtil.asString(data.opt("player_1")),
            JsonUtil.asString(data.opt("stream_url")),
            JsonUtil.asString(data.opt("url")),
        )) {
            val value = unescape(candidate).trim()
            if (isPlayableUrl(value)) return value
        }
        val flv = unescape(JsonUtil.asString(data.opt("flv_url"))).trim()
        if (isDirectMediaUrl(flv)) return flv
        throw IllegalStateException("斗鱼取流响应里没有可播放地址")
    }

    private fun resolveRate(quality: String, rates: List<RateVariant>): RateVariant? {
        if (rates.isEmpty()) return null
        val trimmed = quality.trim()
        val lower = trimmed.lowercase()
        trimmed.toIntOrNull()?.let { n ->
            rates.firstOrNull { it.rate == n }?.let { return it }
        }
        if (lower.startsWith("rate-")) {
            lower.removePrefix("rate-").toIntOrNull()?.let { n ->
                rates.firstOrNull { it.rate == n }?.let { return it }
            }
        }
        if (lower == "source" || lower == "origin" || trimmed.contains("原画")) {
            rates.firstOrNull { it.rate == 0 }?.let { return it }
            rates.firstOrNull { it.name.contains("原画") }?.let { return it }
            return rates.first()
        }
        return rates.firstOrNull { it.name.contains(trimmed) }
    }

    private fun rateLabel(rate: RateVariant): String {
        val name = rate.name.trim()
        return if (name.isEmpty() || name.equals("default", true)) "默认" else name
    }

    private fun detectFormat(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return if (path.contains(".m3u8")) "hls" else "flv"
    }

    // ---- Headers / cookie -------------------------------------------------

    private fun requestHeadersForDid(roomId: String, requestDid: String): Map<String, String> {
        val referer = if (roomId.isEmpty()) "https://www.douyu.com/" else refererFor(roomId)
        return mapOf(
            "accept" to "application/json, text/plain, */*",
            "accept-language" to "zh-CN,zh;q=0.9,en;q=0.7",
            "origin" to "https://www.douyu.com",
            "referer" to referer,
            "user-agent" to DouyuConstants.DESKTOP_UA,
            "cookie" to requestCookie(requestDid),
        )
    }

    /** Full cookie for passport refresh (includes LTP0). */
    private fun fullCookie(): String {
        val fields = parseCookieFields(cookie).toMutableMap()
        fields["dy_did"] = did
        fields["acf_did"] = did
        return buildCookieString(
            fields,
            listOf("dy_auth", "acf_auth", "acf_jwt_token", "dy_did", "acf_did", "LTP0"),
        )
    }

    private fun requestCookie(requestDid: String): String {
        val fields = parseCookieFields(cookie).toMutableMap()
        fields.remove("LTP0")
        val h5Session = !fields["acf_auth"].isNullOrBlank() && !fields["acf_jwt_token"].isNullOrBlank()
        if (h5Session) {
            val filtered = linkedMapOf(
                "dy_did" to requestDid,
                "acf_did" to requestDid,
            )
            fields.forEach { (name, value) ->
                if (value.isEmpty() || name == "dy_did" || name == "acf_did") return@forEach
                if (name.startsWith("acf_")) {
                    filtered[name] = value
                }
            }
            return buildCookieString(
                filtered,
                listOf(
                    "dy_did", "acf_did",
                    "acf_auth", "acf_jwt_token",
                    "acf_uid", "acf_stk", "acf_username", "acf_nickname",
                ),
            )
        }
        val filtered = linkedMapOf(
            "dy_did" to requestDid,
            "acf_did" to requestDid,
        )
        fields["dy_auth"]?.takeIf { it.isNotBlank() }?.let { filtered["dy_auth"] = it }
        return buildCookieString(filtered, listOf("dy_did", "acf_did", "dy_auth"))
    }

    private fun cookiesFromResponse(response: Response): List<Cookie> {
        val url = response.request.url
        return response.headers("Set-Cookie").mapNotNull { header ->
            Cookie.parse(url, header)
        }
    }

    private fun mergeCookieList(
        dst: MutableMap<String, String>,
        cookies: List<Cookie>,
        override: Boolean,
    ) {
        for (ck in cookies) {
            if (ck.name.isEmpty() || ck.value.isEmpty()) continue
            if (ck.domain.isNotEmpty() && !ck.domain.lowercase().contains("douyu.com")) continue
            if (!override && dst.containsKey(ck.name)) continue
            dst[ck.name] = ck.value
        }
    }

    // ---- Misc helpers -----------------------------------------------------

    private fun buildCategoryGroups(cate1: JSONArray, cate2: JSONArray): List<CategoryGroup> {
        val groups = mutableListOf<CategoryGroup>()
        for (i in 0 until cate1.length()) {
            val c1 = cate1.optJSONObject(i) ?: continue
            val cate1Id = JsonUtil.asString(c1.opt("cate1Id"))
            if (cate1Id.isEmpty()) continue
            val categories = mutableListOf<Category>()
            val seen = mutableSetOf<String>()
            var hasTogetherWatch = false
            for (j in 0 until cate2.length()) {
                val c2 = cate2.optJSONObject(j) ?: continue
                if (JsonUtil.asString(c2.opt("cate1Id")) != cate1Id) continue
                val cate2Id = JsonUtil.asString(c2.opt("cate2Id"))
                if (cate2Id.isEmpty() || !seen.add(cate2Id)) continue
                val name = c2.optString("cate2Name")
                if (name == "一起看") {
                    if (hasTogetherWatch) continue
                    hasTogetherWatch = true
                }
                categories += Category(id = cate2Id, name = name)
            }
            if (cate1Id == "2" && !hasTogetherWatch) {
                categories += Category(id = "208", name = "一起看")
            }
            if (categories.isNotEmpty()) {
                groups += CategoryGroup(
                    id = cate1Id,
                    name = c1.optString("cate1Name"),
                    categories = categories,
                )
            }
        }
        return groups
    }

    private fun locateRoomObject(payload: JSONObject): JSONObject? {
        val data = payload.optJSONObject("data")
        if (data != null) {
            return data.optJSONObject("room") ?: data
        }
        return payload.optJSONObject("room") ?: payload
    }

    private fun nestedString(obj: JSONObject, vararg path: String): String {
        var current: JSONObject = obj
        path.forEachIndexed { index, key ->
            val next = current.optJSONObject(key)
            if (next == null) {
                return if (index == path.lastIndex) JsonUtil.asString(current.opt(key)) else ""
            }
            current = next
        }
        return ""
    }

    companion object {
        private const val TAG = "DouyuClient"

        private val LOGIN_COOKIE_ORDER = listOf(
            "dy_auth", "acf_auth", "acf_jwt_token", "dy_did", "acf_did",
            "LTP0", "acf_uid", "acf_stk", "acf_username",
        )

        fun generateDeviceId(): String =
            try {
                UUID.randomUUID().toString().replace("-", "")
            } catch (_: Exception) {
                DouyuConstants.DEFAULT_DID
            }

        fun refererFor(roomId: String): String = "https://www.douyu.com/$roomId"

        fun isLiveFromFlags(showStatus: Long, isLoop: Long, videoLoop: Long): Boolean {
            if (showStatus != 1L) return false
            return isLoop != 1L && videoLoop != 1L
        }

        fun firstNonEmpty(vararg values: String): String =
            values.firstOrNull { it.isNotBlank() }.orEmpty()

        fun parseCookieFields(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            return raw.split(';')
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) return@mapNotNull null
                    val name = part.substring(0, idx).trim()
                    val value = part.substring(idx + 1).trim()
                    if (name.isEmpty()) null else name to value
                }.toMap()
        }

        /** `(acf_auth && acf_jwt_token) || dy_auth` (Go `cookieHasLogin`). */
        fun cookieHasLogin(raw: String): Boolean {
            val fields = parseCookieFields(raw)
            val h5 = !fields["acf_auth"].isNullOrBlank() &&
                !fields["acf_jwt_token"].isNullOrBlank()
            return h5 || !fields["dy_auth"].isNullOrBlank()
        }

        fun cookieUserName(raw: String): String = decodeCookieNickname(raw)

        fun buildCookieString(fields: Map<String, String>, order: List<String>): String {
            val parts = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            for (name in order) {
                val value = fields[name]
                if (!value.isNullOrEmpty()) {
                    parts += "$name=$value"
                    seen += name
                }
            }
            val rest = fields
                .filter { (name, value) -> name !in seen && value.isNotEmpty() }
                .toSortedMap()
                .map { (name, value) -> "$name=$value" }
            parts += rest
            return parts.joinToString("; ")
        }

        fun buildLoginCallbackUrl(raw: String, nowMs: Long): String {
            var login = raw.trim()
            login = when {
                login.startsWith("//") -> "https:$login"
                login.startsWith("http://") || login.startsWith("https://") -> login
                else -> "https://passport.douyu.com/" + login.trimStart('/')
            }
            val separator = if (login.contains('?')) "&" else "?"
            return "$login${separator}callback=appClient_json_callback&_=$nowMs"
        }

        fun parseJsonpObject(body: String): JSONObject {
            val start = body.indexOf('{')
            val end = body.lastIndexOf('}')
            if (start < 0 || end < start) {
                throw IllegalStateException("响应不是 JSON/JSONP 对象")
            }
            return JSONObject(body.substring(start, end + 1))
        }

        fun stringFromQrData(value: Any?): String =
            when (value) {
                null, JSONObject.NULL -> ""
                is String -> value.trim()
                is Number -> value.toString()
                is Boolean -> value.toString()
                else -> value.toString().trim()
            }

        fun douyuAvatarUrl(raw: String): String {
            val full = JsonUtil.fullUrl(DouyuConstants.AVATAR_PREFIX, raw)
            if (full.isEmpty()) return ""
            return try {
                val uri = java.net.URI(full)
                val host = uri.host?.lowercase().orEmpty()
                if (!host.contains("douyucdn.cn")) return full
                val path = uri.path.orEmpty()
                if (!path.contains("/upload/")) return full
                val name = path.substringAfterLast('/')
                if (name.isEmpty() || name.contains('.')) return full
                full + "_big.jpg"
            } catch (_: Exception) {
                full
            }
        }

        fun unescape(value: String): String =
            try {
                // HTML entities that appear in rtmp_live paths.
                value
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
            } catch (_: Exception) {
                value
            }

        fun isPlayableUrl(value: String): Boolean {
            if (value.isBlank()) return false
            return try {
                val uri = java.net.URI(value)
                uri.host != null && uri.scheme in setOf("http", "https", "rtmp")
            } catch (_: Exception) {
                false
            }
        }

        fun isDirectMediaUrl(value: String): Boolean {
            if (!isPlayableUrl(value)) return false
            val path = value.substringBefore('?').lowercase()
            return path.endsWith(".flv") || path.endsWith(".m3u8") || path.endsWith(".mp4")
        }

        /**
         * pure_live / Go patch: anonymous ws CDN links with expire=300 get expire=0
         * so the edge uses its default TTL instead of a hard 5-minute cut.
         */
        fun applyExpirePatch(rawUrl: String): String {
            return if (rawUrl.contains("expire=300") && rawUrl.contains("fcdn=ws")) {
                "$rawUrl&expire=0"
            } else {
                rawUrl
            }
        }

        fun md5Hex(value: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        @Suppress("unused")
        fun decodeCookieNickname(raw: String): String {
            val name = parseCookieFields(raw)["acf_nickname"]
                ?: parseCookieFields(raw)["acf_username"]
                ?: return ""
            return try {
                URLDecoder.decode(name, Charsets.UTF_8.name()).trim()
            } catch (_: Exception) {
                name
            }
        }
    }
}

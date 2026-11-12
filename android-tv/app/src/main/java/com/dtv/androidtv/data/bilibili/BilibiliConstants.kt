package com.dtv.androidtv.data.bilibili

/** Endpoint + UA constants aligned with Go `internal/platforms/bilibili`. */
internal object BilibiliConstants {
    /**
     * Default browse area: 网游 / 英雄联盟.
     * Area ids are always `parent_child` (list API needs both).
     */
    const val DEFAULT_AREA_ID = "2_86"
    const val DEFAULT_AREA_NAME = "英雄联盟"

    /** Upstream list page size is fixed (~40); limit is display-only. */
    const val AREA_PAGE_SIZE = 40

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    const val REFERER = "https://live.bilibili.com/"
    const val ORIGIN = "https://live.bilibili.com"

    const val URL_AREA_LIST = "https://api.live.bilibili.com/room/v1/Area/getList"
    const val URL_LIVE_LIST = "https://api.live.bilibili.com/xlive/web-interface/v1/second/getList"
    const val URL_ROOM_INIT = "https://api.live.bilibili.com/room/v1/Room/room_init"
    const val URL_ROOM_INFO = "https://api.live.bilibili.com/xlive/web-room/v1/index/getInfoByRoom"
    const val URL_ROOM_PLAY_INFO =
        "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo"
    const val URL_WBI_NAV = "https://api.bilibili.com/x/web-interface/nav"

    /** Web QR login (passport) — mirrors Go `qrlogin.go`. */
    const val URL_QR_GENERATE =
        "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
    const val URL_QR_POLL =
        "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
    const val URL_LOGIN_NAV = "https://api.bilibili.com/x/web-interface/nav"
    const val URL_LOGOUT = "https://passport.bilibili.com/login/exit/v2"

    /** Match web header source on poll to reduce risk control false positives. */
    const val QR_POLL_SOURCE = "main-fe-header"

    /** Official QR TTL (seconds). */
    const val QR_EXPIRES_IN_SEC = 180

    /** www origin for passport QR / logout (not live subdomain). */
    const val WWW_REFERER = "https://www.bilibili.com/"
    const val WWW_ORIGIN = "https://www.bilibili.com"

    /**
     * Cookie key order when assembling the login Cookie header
     * (SESSDATA / bili_jct first for debugging).
     */
    val COOKIE_KEY_ORDER = listOf(
        "SESSDATA",
        "bili_jct",
        "DedeUserID",
        "DedeUserID__ckMd5",
        "sid",
        "buvid3",
        "buvid4",
    )

    /** Pages that may embed `access_id` (→ w_webid). SSR is flaky; try several. */
    val W_WEBID_PAGES = listOf(
        "https://live.bilibili.com/lol",
        "https://live.bilibili.com/",
        "https://live.bilibili.com/p/eden/area-tags",
    )

    /** Cache TTLs — JWT ttl is 24h / WBI rotates daily; 30m is conservative. */
    const val W_WEBID_TTL_MS = 30L * 60L * 1000L
    const val WBI_KEY_TTL_MS = 30L * 60L * 1000L
    const val AREA_CACHE_TTL_MS = 30L * 60L * 1000L

    /** Preferred qn ladder exposed to the UI (高端档并入原画). */
    val PREFERRED_QN = longArrayOf(10_000L, 400L, 250L, 150L, 80L)

    const val QN_ORIGIN = 10_000L
    const val QN_BLURAY = 400L
    const val QN_ULTRA = 250L
    const val QN_HIGH = 150L
    const val QN_SMOOTH = 80L

    /** Concurrent getRoomPlayInfo fetches (one request per qn). */
    const val MAX_VARIANT_FETCH = 4

    /**
     * Fixed secret for second/getList `w_rid` (md5 of ordered query + secret).
     * Distinct from WBI mixin_key — do not mix the two signing schemes.
     */
    const val LIST_SIGN_SECRET = "ea1db124af3c7062474693fa704f4ff8"
}

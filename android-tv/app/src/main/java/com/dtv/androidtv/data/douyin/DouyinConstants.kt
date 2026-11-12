package com.dtv.androidtv.data.douyin

/**
 * Endpoint + UA constants aligned with Go `internal/platforms/douyin`.
 *
 * **UA must stay in lockstep with [ABogus]** — the user-agent string is mixed
 * into the a_bogus SM3/RC4 payload. Changing either side alone breaks signing
 * and Douyin returns empty bodies.
 */
internal object DouyinConstants {
    /**
     * Default browse partition: 和平精英 (`partition_type_partition`).
     * First entry in the static categories embed.
     */
    const val DEFAULT_PARTITION = "1_1010032"
    const val DEFAULT_PARTITION_NAME = "和平精英"

    /** Upstream list page size is fixed at 15. */
    const val LIST_PAGE_SIZE = 15

    /**
     * Desktop UA baked into a_bogus (Go `desktopUA`).
     * Do not swap for a generic Chrome string without regenerating sign tests.
     */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/116.0.5845.97 Safari/537.36 Core/1.116.567.400 QQBrowser/19.7.6764.400"

    const val REFERER = "https://live.douyin.com/"
    const val URL_LIVE_HOME = "https://live.douyin.com/"
    const val URL_PARTITION_V2 =
        "https://live.douyin.com/webcast/web/partition/detail/room/v2/"
    const val URL_ROOM_ENTER = "https://live.douyin.com/webcast/room/web/enter/"
    /** Room detail + pull URLs (web/enter returns empty body on current params). */
    const val URL_REFLOW = "https://webcast.amemv.com/webcast/room/reflow/info/"

    /** Web aid / live_id — also appear inside the a_bogus main table. */
    const val PARAM_AID = "6383"
    const val PARAM_LIVE_ID = "1"

    /** Reflow-only constants (missing → app id / live id mismatch). */
    const val REFLOW_TYPE_ID = "0"
    const val REFLOW_VERSION_CODE = "99.99.99"
    const val REFLOW_APP_ID = "1128"

    /** Cookie warm cache TTL (Go: 30 minutes). */
    const val WARM_TTL_MS = 30L * 60L * 1000L

    /** msToken length used by list + reflow (server only checks presence). */
    const val MS_TOKEN_LENGTH = 107
}

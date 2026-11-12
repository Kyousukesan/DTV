package com.dtv.androidtv.data.huya

/** Endpoint + UA constants aligned with Go `internal/platforms/huya`. */
internal object HuyaConstants {
    const val DEFAULT_PAGE_SIZE = 20

    /** Default browse gid: 英雄联盟 (same numeric id as Douyu LOL cate2). */
    const val DEFAULT_GID = "1"
    const val DEFAULT_GID_NAME = "英雄联盟"

    /** Chrome UA used by live-list / search (Go `common.DefaultUA`). */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /** Firefox UA required by profileRoom (differs from list Chrome UA). */
    const val FIREFOX_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:123.0) Gecko/20100101 Firefox/123.0"

    /** HYSDK UA for WUP CDN-token RPC (pure_live / Go). */
    const val HYSDK_UA =
        "HYSDK(Windows,30000002)_APP(pc_exe&7080000&official)_SDK(trans&2.34.0.5795)"

    const val REFERER = "https://www.huya.com/"
    const val WUP_ORIGIN = "https://m.huya.com/"
    const val WUP_REFERER = "https://m.huya.com/"

    const val URL_LIVE_LIST =
        "https://live.huya.com/liveHttpUI/getLiveList?iGid=%s&iPageNo=%d&iPageSize=%d"
    const val URL_PROFILE_ROOM =
        "https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=%s&showSecret=1"
    const val URL_WUP = "http://wup.huya.com"

    /** Danmaku WebSocket gateway (Go `urlDanmakuWS`). */
    const val URL_DANMAKU_WS = "wss://cdnws.api.huya.com"

    const val WUP_SERVANT = "liveui"
    const val WUP_FUNC = "getCdnTokenInfoEx"
    const val WUP_APP_ID = 66
    const val WUP_HYUA = "pc_exe&7060000&official"
    const val WUP_LOOP_TIME = 0

    /** Classpath resource copied from Go `internal/platforms/huya/assets/categories.json`. */
    const val CATEGORIES_RESOURCE = "huya/categories.json"
}

package com.dtv.androidtv.data.douyu

/** Endpoint + UA constants aligned with Go `internal/platforms/douyu`. */
internal object DouyuConstants {
    const val DEFAULT_DID = "10000000000000000000000000001501"
    const val DEFAULT_CDN = "ws-h5"
    const val DEFAULT_PAGE_SIZE = 30

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1"

    const val COVER_PREFIX = "https://rpic.douyucdn.cn/"
    const val AVATAR_PREFIX = "https://apic.douyucdn.cn/upload/"

    const val URL_BETARD = "https://www.douyu.com/betard/%s"
    const val URL_GET_ENCRYPTION =
        "https://www.douyu.com/wgapi/livenc/liveweb/websec/getEncryption?did=%s"
    const val URL_GET_H5_PLAY = "https://www.douyu.com/lapi/live/getH5PlayV1/%s"
    const val URL_CATE_LIST = "https://m.douyu.com/api/cate/list"
    const val URL_DIRECTORY = "https://www.douyu.com/gapi/rkc/directory/%s/%d?limit=%d"

    /** Passport QR login (Go `qrlogin.go`). */
    const val URL_QR_GENERATE = "https://passport.douyu.com/scan/generateCode"
    const val URL_QR_CHECK = "https://passport.douyu.com/japi/scan/auth"
    const val URL_SAFE_AUTH =
        "https://passport.douyu.com/lapi/passport/iframe/safeAuth?client_id=1"
    const val QR_LOGIN_REFERER =
        "https://passport.douyu.com/index/login?type=login&client_id=1"

    /** Known public HLS sample for Media3 smoke when Douyu is unreachable. */
    const val TEST_HLS_URL =
        "https://devstreaming-cdn.apple.com/videos/streaming/examples/" +
            "img_bipbop_adv_example_fmp4/master.playlist.m3u8"

    /** Default Slice-1 browse cate2: 英雄联盟 (LOL). */
    const val DEFAULT_CATE2_ID = "1"
    const val DEFAULT_CATE2_NAME = "英雄联盟"
}

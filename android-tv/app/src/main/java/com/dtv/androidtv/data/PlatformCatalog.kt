package com.dtv.androidtv.data

import com.dtv.androidtv.data.auth.AuthStore
import com.dtv.androidtv.data.bilibili.BilibiliClient
import com.dtv.androidtv.data.bilibili.BilibiliConstants
import com.dtv.androidtv.data.bilibili.BilibiliDtvApi
import com.dtv.androidtv.data.douyin.DouyinConstants
import com.dtv.androidtv.data.douyin.DouyinDtvApi
import com.dtv.androidtv.data.douyu.DouyuClient
import com.dtv.androidtv.data.douyu.DouyuConstants
import com.dtv.androidtv.data.huya.HuyaConstants
import com.dtv.androidtv.data.huya.HuyaDtvApi
import com.dtv.androidtv.data.model.AuthAccount
import com.dtv.androidtv.data.model.LivePlatform

/**
 * Process-wide platform clients + [DtvApi] map.
 *
 * Douyu / Bilibili clients are singletons so QR cookies stick for browse + play.
 */
class PlatformCatalog(
    val douyuClient: DouyuClient = DouyuClient(),
    val bilibiliClient: BilibiliClient = BilibiliClient(),
) {
    private val apis: Map<LivePlatform, DtvApi> = mapOf(
        LivePlatform.DOUYU to DouyuDtvApi(douyuClient),
        LivePlatform.HUYA to HuyaDtvApi(),
        LivePlatform.DOUYIN to DouyinDtvApi(),
        LivePlatform.BILIBILI to BilibiliDtvApi(bilibiliClient),
    )

    @Volatile
    var active: LivePlatform = LivePlatform.DOUYU

    fun api(platform: LivePlatform = active): DtvApi =
        apis[platform] ?: error("platform ${platform.id} not wired")

    fun apiFor(platformId: String): DtvApi = api(LivePlatform.fromId(platformId))

    fun defaultCategoryId(platform: LivePlatform = active): String =
        when (platform) {
            LivePlatform.DOUYU -> DouyuConstants.DEFAULT_CATE2_ID
            LivePlatform.HUYA -> HuyaConstants.DEFAULT_GID
            LivePlatform.DOUYIN -> DouyinConstants.DEFAULT_PARTITION
            LivePlatform.BILIBILI -> BilibiliConstants.DEFAULT_AREA_ID
        }

    fun label(platform: LivePlatform): String =
        when (platform) {
            LivePlatform.DOUYU -> "斗鱼"
            LivePlatform.HUYA -> "虎牙"
            LivePlatform.DOUYIN -> "抖音"
            LivePlatform.BILIBILI -> "B站"
        }

    /** Platforms with TV QR login (Huya/Douyin skipped — no desktop account QR). */
    val loginPlatforms: List<LivePlatform> = listOf(LivePlatform.DOUYU, LivePlatform.BILIBILI)

    /**
     * Restore cookies from [AuthStore] into live clients.
     * Douyu: refresh via safeAuth; Bilibili: validate via nav.
     */
    fun restoreAuth(store: AuthStore): List<AuthAccount> {
        val out = mutableListOf<AuthAccount>()
        for (platform in loginPlatforms) {
            val cookie = store.cookie(platform)
            if (cookie.isBlank()) {
                out += AuthAccount(platform = platform.id, loggedIn = false)
                continue
            }
            when (platform) {
                LivePlatform.DOUYU -> {
                    douyuClient.setCookie(cookie)
                    val refreshed = runCatching { douyuClient.refreshCookie() }.getOrNull()
                    val account = douyuClient.cookieValid()
                    if (!account.loggedIn) {
                        store.clear(platform)
                        douyuClient.setCookie("")
                        out += AuthAccount(platform = platform.id, loggedIn = false)
                    } else {
                        val name = account.userName
                        val finalCookie = refreshed?.takeIf { it.isNotBlank() } ?: douyuClient.cookie()
                        store.saveSession(platform, finalCookie, userName = name)
                        out += AuthAccount(platform = platform.id, loggedIn = true, userName = name)
                    }
                }
                LivePlatform.BILIBILI -> {
                    bilibiliClient.setCookie(cookie)
                    val profile = runCatching { bilibiliClient.loginProfile() }.getOrNull()
                    if (profile == null || !profile.loggedIn) {
                        store.clear(platform)
                        bilibiliClient.setCookie("")
                        out += AuthAccount(platform = platform.id, loggedIn = false)
                    } else {
                        store.saveSession(
                            platform,
                            bilibiliClient.cookie(),
                            userName = profile.userName,
                            avatar = profile.avatar,
                        )
                        out += AuthAccount(
                            platform = platform.id,
                            loggedIn = true,
                            userName = profile.userName,
                            avatar = profile.avatar,
                        )
                    }
                }
                else -> Unit
            }
        }
        return out
    }
}

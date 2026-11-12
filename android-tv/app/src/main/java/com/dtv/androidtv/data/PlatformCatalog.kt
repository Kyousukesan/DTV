package com.dtv.androidtv.data

import com.dtv.androidtv.data.bilibili.BilibiliDtvApi
import com.dtv.androidtv.data.bilibili.BilibiliConstants
import com.dtv.androidtv.data.douyin.DouyinConstants
import com.dtv.androidtv.data.douyin.DouyinDtvApi
import com.dtv.androidtv.data.douyu.DouyuConstants
import com.dtv.androidtv.data.huya.HuyaConstants
import com.dtv.androidtv.data.huya.HuyaDtvApi
import com.dtv.androidtv.data.model.LivePlatform

/**
 * Process-wide map of platform → [DtvApi]. Browse switches [active];
 * player / library resolve by Streamer.platform so room ids can collide.
 */
class PlatformCatalog(
    private val apis: Map<LivePlatform, DtvApi> = defaultApis(),
) {
    @Volatile
    var active: LivePlatform = LivePlatform.DOUYU

    fun api(platform: LivePlatform = active): DtvApi =
        apis[platform] ?: error("platform ${platform.id} not wired")

    fun apiFor(platformId: String): DtvApi = api(LivePlatform.fromId(platformId))

    /** Preferred first-open category id when switching platforms. */
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

    companion object {
        fun defaultApis(): Map<LivePlatform, DtvApi> = mapOf(
            LivePlatform.DOUYU to DouyuDtvApi(),
            LivePlatform.HUYA to HuyaDtvApi(),
            LivePlatform.DOUYIN to DouyinDtvApi(),
            LivePlatform.BILIBILI to BilibiliDtvApi(),
        )
    }
}

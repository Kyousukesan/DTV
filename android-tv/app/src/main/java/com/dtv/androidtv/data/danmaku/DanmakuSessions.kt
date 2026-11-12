package com.dtv.androidtv.data.danmaku

import com.dtv.androidtv.data.PlatformCatalog
import com.dtv.androidtv.data.bilibili.BilibiliDanmakuManager
import com.dtv.androidtv.data.douyin.DouyinDanmakuManager
import com.dtv.androidtv.data.douyu.DouyuDanmakuManager
import com.dtv.androidtv.data.huya.HuyaDanmakuManager
import com.dtv.androidtv.data.model.LivePlatform
import kotlinx.coroutines.CoroutineScope

/** Factory for platform danmaku sessions (LT-9). */
object DanmakuSessions {
    fun create(
        platform: LivePlatform,
        scope: CoroutineScope,
        catalog: PlatformCatalog,
    ): DanmakuSession? =
        when (platform) {
            LivePlatform.DOUYU -> DouyuDanmakuManager(scope)
            LivePlatform.HUYA -> HuyaDanmakuManager(scope)
            LivePlatform.DOUYIN -> DouyinDanmakuManager(scope)
            LivePlatform.BILIBILI -> BilibiliDanmakuManager(scope, catalog.bilibiliClient)
        }

    fun supported(platform: LivePlatform): Boolean = true
}

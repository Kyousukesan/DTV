package com.dtv.androidtv.data

import com.dtv.androidtv.data.bilibili.BilibiliClient
import com.dtv.androidtv.data.bilibili.BilibiliConstants
import com.dtv.androidtv.data.douyin.DouyinClient
import com.dtv.androidtv.data.douyin.DouyinConstants
import com.dtv.androidtv.data.huya.HuyaClient
import com.dtv.androidtv.data.huya.HuyaConstants
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional live network smoke: list + resolve one playable URL per platform.
 * Enable: `-Ddtv.liveMultiPlatformSmoke=true`
 */
class LiveMultiPlatformSmokeTest {
    @Test
    fun huyaListAndResolve() {
        assumeEnabled()
        val client = HuyaClient()
        val page = client.fetchLiveList(HuyaConstants.DEFAULT_GID, page = 1)
        assertTrue("huya list empty", page.items.isNotEmpty())
        val room = page.items.first { it.isLive }
        val info = client.streamInfo(room.id)
        assertTrue("huya variants empty room=${room.id}", info.variants.isNotEmpty())
        val url = client.streamUrl(room.id, info.variants.first().id, info.lines.firstOrNull()?.id.orEmpty())
        println("HUYA ok room=${room.id} nick=${room.nickname} format=${info.variants.first().format} url=${url.take(80)}…")
        assertTrue(url.startsWith("http"))
    }

    @Test
    fun douyinListAndResolve() {
        assumeEnabled()
        val client = DouyinClient()
        val page = client.fetchLiveList(DouyinConstants.DEFAULT_PARTITION, page = 1)
        assertTrue("douyin list empty", page.items.isNotEmpty())
        val room = page.items.first { it.isLive }
        val info = client.streamInfo(room.id)
        assertTrue("douyin variants empty room=${room.id}", info.variants.isNotEmpty())
        val url = client.streamUrl(room.id, info.variants.first().id, "")
        println("DOUYIN ok room=${room.id} nick=${room.nickname} format=${info.variants.first().format} url=${url.take(80)}…")
        assertTrue(url.startsWith("http"))
    }

    @Test
    fun bilibiliListAndResolve() {
        assumeEnabled()
        val client = BilibiliClient()
        val page = client.fetchLiveList(BilibiliConstants.DEFAULT_AREA_ID, page = 1)
        assertTrue("bilibili list empty", page.items.isNotEmpty())
        val room = page.items.first { it.isLive }
        val info = client.streamInfo(room.id)
        assertTrue("bilibili variants empty room=${room.id}", info.variants.isNotEmpty())
        val url = client.streamUrl(room.id, info.variants.first().id, "")
        println("BILI ok room=${room.id} nick=${room.nickname} format=${info.variants.first().format} url=${url.take(80)}…")
        assertTrue(url.contains(".m3u8") || info.variants.first().format.equals("hls", true))
        assertTrue(url.startsWith("http"))
    }

    private fun assumeEnabled() {
        assumeTrue(
            "set -Ddtv.liveMultiPlatformSmoke=true",
            System.getProperty("dtv.liveMultiPlatformSmoke") == "true",
        )
    }
}

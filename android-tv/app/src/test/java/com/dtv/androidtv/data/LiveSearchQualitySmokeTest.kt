package com.dtv.androidtv.data

import com.dtv.androidtv.data.bilibili.BilibiliClient
import com.dtv.androidtv.data.bilibili.BilibiliConstants
import com.dtv.androidtv.data.douyin.DouyinClient
import com.dtv.androidtv.data.douyin.DouyinConstants
import com.dtv.androidtv.data.douyin.DouyinDtvApi
import com.dtv.androidtv.data.douyu.DouyuClient
import com.dtv.androidtv.data.huya.HuyaClient
import com.dtv.androidtv.data.huya.HuyaConstants
import com.dtv.androidtv.data.model.SearchHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional live smoke for LT-10 search + quality/line exposure.
 * Enable: `-Ddtv.liveSearchQualitySmoke=true`
 */
class LiveSearchQualitySmokeTest {
    @Test
    fun douyuSearchAndQualities() {
        assumeEnabled()
        val client = DouyuClient()
        val hits = client.searchAnchor("王者")
        println("DOUYU search hits=${hits.size} first=${hits.firstOrNull()?.let { "${it.nickname}/${it.id} live=${it.isLive}" }}")
        assertTrue("douyu search empty", hits.isNotEmpty())
        // Prefer a live hit so StreamInfo exposes rate/line ladders for the report.
        val candidates = hits.filter { it.isLive }.ifEmpty { hits }
        var info: com.dtv.androidtv.data.model.StreamInfo? = null
        for (room in candidates.take(5)) {
            val probed = runCatching { client.streamInfo(room.id) }.getOrNull() ?: continue
            println(
                "DOUYU probe room=${probed.roomId} live=${probed.isLive} " +
                    "variants=${probed.variants.map { it.label }} lines=${probed.lines.map { it.label }}",
            )
            if (probed.isLive && probed.variants.isNotEmpty()) {
                info = probed
                break
            }
        }
        assertTrue("douyu no live stream from search hits", info != null)
        assertTrue(info!!.variants.isNotEmpty())
        assertTrue(info.lines.isNotEmpty())
    }

    @Test
    fun huyaSearchAndQualities() {
        assumeEnabled()
        val client = HuyaClient()
        val hits = client.searchAnchor("王者")
        println("HUYA search hits=${hits.size} first=${hits.firstOrNull()?.let { "${it.nickname}/${it.id} live=${it.isLive}" }}")
        assertTrue("huya search empty", hits.isNotEmpty())
        val page = client.fetchLiveList(HuyaConstants.DEFAULT_GID, page = 1)
        val room = page.items.first { it.isLive }
        val info = client.streamInfo(room.id)
        println(
            "HUYA stream room=${info.roomId} live=${info.isLive} " +
                "variants=${info.variants.map { it.label }} lines=${info.lines.map { it.label }}",
        )
        assertTrue(info.variants.isNotEmpty())
        assertTrue(info.lines.isNotEmpty())
    }

    @Test
    fun bilibiliSearchAndQualities() {
        assumeEnabled()
        val client = BilibiliClient()
        val hits = client.searchRoom("王者")
        println("BILI search hits=${hits.size} first=${hits.firstOrNull()?.let { "${it.nickname}/${it.id} live=${it.isLive}" }}")
        assertTrue("bili search empty", hits.isNotEmpty())
        val page = client.fetchLiveList(BilibiliConstants.DEFAULT_AREA_ID, page = 1)
        val room = page.items.first { it.isLive }
        val info = client.streamInfo(room.id)
        println(
            "BILI stream room=${info.roomId} live=${info.isLive} " +
                "variants=${info.variants.map { it.label }} lines=${info.lines.map { it.label }}",
        )
        assertTrue(info.variants.isNotEmpty())
    }

    @Test
    fun douyinRoomIdSearchAndQualities() {
        assumeEnabled()
        val client = DouyinClient()
        val page = client.fetchLiveList(DouyinConstants.DEFAULT_PARTITION, page = 1)
        assertTrue(page.items.isNotEmpty())
        val room = page.items.first { it.isLive }
        val api = DouyinDtvApi(client)
        // Room-id-only search (mirrors desktop).
        val hits: List<SearchHit> = runBlocking { api.search(room.id) }
        println("DOUYIN search roomId=${room.id} hits=${hits.size} nick=${hits.firstOrNull()?.nickname}")
        assertTrue(hits.size == 1)
        val info = client.streamInfo(room.id)
        println(
            "DOUYIN stream room=${info.roomId} live=${info.isLive} " +
                "variants=${info.variants.map { it.label }} lines=${info.lines.size}",
        )
        assertTrue(info.variants.isNotEmpty())
        assertTrue("douyin has no CDN lines", info.lines.isEmpty())
    }

    private fun assumeEnabled() {
        assumeTrue(
            "set -Ddtv.liveSearchQualitySmoke=true",
            System.getProperty("dtv.liveSearchQualitySmoke") == "true",
        )
    }
}

package com.dtv.androidtv.data.huya

import com.dtv.androidtv.data.DtvApi
import com.dtv.androidtv.data.model.CategoryGroup
import com.dtv.androidtv.data.model.SearchHit
import com.dtv.androidtv.data.model.StreamInfo
import com.dtv.androidtv.data.model.Streamer
import com.dtv.androidtv.data.model.StreamerPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Huya-backed [DtvApi] — blocking OkHttp / WUP calls hop to IO. */
class HuyaDtvApi(
    private val client: HuyaClient = HuyaClient(),
) : DtvApi {
    override suspend fun listCategories(): List<CategoryGroup> =
        withContext(Dispatchers.IO) { client.fetchCategories() }

    override suspend fun listStreamers(categoryId: String, page: Int): StreamerPage =
        withContext(Dispatchers.IO) { client.fetchLiveList(categoryId, page) }

    override suspend fun getStreamInfo(roomId: String, line: String?): StreamInfo =
        withContext(Dispatchers.IO) { client.streamInfo(roomId, line.orEmpty()) }

    override suspend fun getStreamUrl(roomId: String, quality: String, line: String): String =
        withContext(Dispatchers.IO) { client.streamUrl(roomId, quality, line) }

    override suspend fun getRoomInfo(roomId: String): Streamer =
        withContext(Dispatchers.IO) { client.fetchRoomInfo(roomId) }

    override suspend fun search(keyword: String, page: Int): List<SearchHit> =
        withContext(Dispatchers.IO) { client.searchAnchor(keyword, page) }
}

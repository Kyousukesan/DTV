package com.dtv.androidtv.data

import com.dtv.androidtv.data.douyu.DouyuClient
import com.dtv.androidtv.data.model.CategoryGroup
import com.dtv.androidtv.data.model.StreamInfo
import com.dtv.androidtv.data.model.StreamerPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Douyu-backed [DtvApi] — blocking OkHttp calls hop to IO. */
class DouyuDtvApi(
    private val client: DouyuClient = DouyuClient(),
) : DtvApi {
    override suspend fun listCategories(): List<CategoryGroup> =
        withContext(Dispatchers.IO) { client.fetchCategories() }

    override suspend fun listStreamers(categoryId: String, page: Int): StreamerPage =
        withContext(Dispatchers.IO) { client.fetchLiveList(categoryId, page) }

    override suspend fun getStreamInfo(roomId: String, line: String?): StreamInfo =
        withContext(Dispatchers.IO) { client.streamInfo(roomId, line.orEmpty()) }

    override suspend fun getStreamUrl(roomId: String, quality: String, line: String): String =
        withContext(Dispatchers.IO) { client.streamUrl(roomId, quality, line) }
}

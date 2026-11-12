package com.dtv.androidtv.data

import com.dtv.androidtv.data.model.CategoryGroup
import com.dtv.androidtv.data.model.StreamInfo
import com.dtv.androidtv.data.model.StreamerPage

/**
 * Stable UI-facing contract mirroring frontend `DtvApi` / Go platform services.
 * Slice-1 exposes Douyu list + stream resolve only.
 */
interface DtvApi {
    suspend fun listCategories(): List<CategoryGroup>

    suspend fun listStreamers(categoryId: String, page: Int = 1): StreamerPage

    suspend fun getStreamInfo(roomId: String, line: String? = null): StreamInfo

    /** Re-resolve a single playable URL (FLV renew / line switch). */
    suspend fun getStreamUrl(roomId: String, quality: String = "", line: String = ""): String
}

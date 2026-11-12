package com.dtv.androidtv.data

import com.dtv.androidtv.data.model.CategoryGroup
import com.dtv.androidtv.data.model.StreamInfo
import com.dtv.androidtv.data.model.Streamer
import com.dtv.androidtv.data.model.StreamerPage

/**
 * Stable UI-facing contract mirroring frontend `DtvApi` / Go platform services.
 * Douyu list + stream resolve + light room meta for follow refresh.
 */
interface DtvApi {
    suspend fun listCategories(): List<CategoryGroup>

    suspend fun listStreamers(categoryId: String, page: Int = 1): StreamerPage

    suspend fun getStreamInfo(roomId: String, line: String? = null): StreamInfo

    /** Re-resolve a single playable URL (FLV renew / line switch). */
    suspend fun getStreamUrl(roomId: String, quality: String = "", line: String = ""): String

    /** Card-level room meta (betard) for follow / recent refresh — no stream URLs. */
    suspend fun getRoomInfo(roomId: String): Streamer
}

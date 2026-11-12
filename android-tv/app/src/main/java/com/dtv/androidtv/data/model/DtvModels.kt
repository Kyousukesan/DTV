package com.dtv.androidtv.data.model

/**
 * Kotlin mirror of Go `internal/platforms/common` + frontend `api/types.ts`.
 * Field names match the JSON contract used by desktop DtvApi.
 */

/** Live platform id; Slice-1 only wires Douyu. */
enum class LivePlatform(val id: String) {
    DOUYU("douyu"),
    HUYA("huya"),
    DOUYIN("douyin"),
    BILIBILI("bilibili");

    companion object {
        fun fromId(raw: String): LivePlatform =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: DOUYU
    }
}

/** Secondary category under a cate1 group. */
data class Category(
    val id: String,
    val name: String,
)

/** cate1 group with nested cate2 list. */
data class CategoryGroup(
    val id: String,
    val name: String,
    val categories: List<Category>,
)

/** Room / streamer card shown in browse rows. */
data class Streamer(
    val id: String,
    val platform: String,
    val nickname: String,
    val title: String,
    val cover: String,
    val avatar: String,
    val viewers: Long,
    val categoryName: String,
    val isLive: Boolean,
)

/** One page of room cards + paging flags. */
data class StreamerPage(
    val items: List<Streamer>,
    val page: Int,
    val hasMore: Boolean,
    val totalPages: Int = 0,
)

/** One quality / rate ladder entry. */
data class StreamVariant(
    val id: String,
    val label: String,
    val url: String,
    /** Douyu H5 play currently yields FLV; keep "hls" for test / future platforms. */
    val format: String,
    val urls: List<String> = emptyList(),
    val appliedRate: Int? = null,
    val unconfirmed: Boolean = false,
)

/** CDN / line option (Douyu cdn id). */
data class LineOption(
    val id: String,
    val label: String,
)

/** Player-page payload: room meta + variants + lines. */
data class StreamInfo(
    val roomId: String,
    val platform: String,
    val title: String,
    val nickname: String,
    val avatar: String,
    val isLive: Boolean,
    val variants: List<StreamVariant>,
    val lines: List<LineOption>,
)

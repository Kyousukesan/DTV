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

/** Danmaku kind; mirrors frontend `DanmakuKind` / Go `common.DanmakuKind`. */
enum class DanmakuKind(val id: String) {
    CHAT("chat"),
    GIFT("gift"),
    SYSTEM("system"),
    ENTER("enter");

    companion object {
        fun fromId(raw: String): DanmakuKind =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: CHAT
    }
}

/**
 * Display mode for the TV overlay (desktop danmu.js: scroll / top / bottom).
 * Douyu chat packets do not carry a mode; TV defaults to [SCROLL].
 */
enum class DanmakuMode(val id: String) {
    SCROLL("scroll"),
    TOP("top"),
    BOTTOM("bottom");

    companion object {
        fun fromId(raw: String?): DanmakuMode =
            entries.firstOrNull { it.id.equals(raw.orEmpty(), ignoreCase = true) } ?: SCROLL
    }
}

/** Normalized danmaku message (Go `common.DanmakuMessage`). */
data class DanmakuMessage(
    val id: String,
    val nickname: String,
    val content: String,
    val level: Int = 0,
    /** `#RRGGBB` when Douyu `col` maps; empty → UI default white. */
    val color: String = "",
    val kind: DanmakuKind = DanmakuKind.CHAT,
    val at: Long = System.currentTimeMillis(),
    val mode: DanmakuMode = DanmakuMode.SCROLL,
)

/** Room-scoped danmaku event payload. */
data class DanmakuEvent(
    val roomId: String,
    val platform: String = LivePlatform.DOUYU.id,
    val message: DanmakuMessage,
)

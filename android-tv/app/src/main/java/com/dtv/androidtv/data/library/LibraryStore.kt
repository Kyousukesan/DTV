package com.dtv.androidtv.data.library

import android.content.Context
import com.dtv.androidtv.data.model.LivePlatform
import com.dtv.androidtv.data.model.Streamer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local persistence for TV home rows: follows + recently watched.
 * Slice/M3: SharedPreferences only; no cloud / LAN sync.
 */
class LibraryStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadFollows(): List<Streamer> = readList(KEY_FOLLOWS)

    fun loadRecent(): List<Streamer> = readList(KEY_RECENT)

    fun isFollowed(roomId: String): Boolean =
        loadFollows().any { it.id == roomId }

    fun isFollowed(platform: String, roomId: String): Boolean =
        loadFollows().any { sameRoom(it, platform, roomId) }

    /** Upsert follow; newest first. Caps at [MAX_FOLLOWS]. Keyed by platform+id. */
    @Synchronized
    fun follow(streamer: Streamer) {
        val platform = streamer.platform.ifBlank { LivePlatform.DOUYU.id }
        val next = listOf(streamer.copy(platform = platform)) +
            loadFollows().filterNot { sameRoom(it, platform, streamer.id) }
        writeList(KEY_FOLLOWS, next.take(MAX_FOLLOWS))
    }

    @Synchronized
    fun unfollow(platform: String, roomId: String) {
        writeList(
            KEY_FOLLOWS,
            loadFollows().filterNot { sameRoom(it, platform, roomId) },
        )
    }

    @Synchronized
    fun unfollow(roomId: String) {
        writeList(KEY_FOLLOWS, loadFollows().filterNot { it.id == roomId })
    }

    fun toggleFollow(streamer: Streamer): Boolean {
        val platform = streamer.platform.ifBlank { LivePlatform.DOUYU.id }
        return if (isFollowed(platform, streamer.id)) {
            unfollow(platform, streamer.id)
            false
        } else {
            follow(streamer)
            true
        }
    }

    /** Record a watch; moves existing entry to front. Caps at [MAX_RECENT]. */
    @Synchronized
    fun recordRecent(streamer: Streamer) {
        if (streamer.id.isBlank() || streamer.id == "test") return
        val platform = streamer.platform.ifBlank { LivePlatform.DOUYU.id }
        val next = listOf(streamer.copy(platform = platform)) +
            loadRecent().filterNot { sameRoom(it, platform, streamer.id) }
        writeList(KEY_RECENT, next.take(MAX_RECENT))
    }

    /** Replace a follow/recent card with fresher room meta (live flag, cover…). */
    @Synchronized
    fun updateCached(streamer: Streamer) {
        val platform = streamer.platform.ifBlank { LivePlatform.DOUYU.id }
        fun merge(list: List<Streamer>): List<Streamer> =
            list.map {
                if (sameRoom(it, platform, streamer.id)) streamer.copy(platform = platform) else it
            }
        writeList(KEY_FOLLOWS, merge(loadFollows()))
        writeList(KEY_RECENT, merge(loadRecent()))
    }

    private fun sameRoom(item: Streamer, platform: String, roomId: String): Boolean =
        item.id == roomId &&
            item.platform.ifBlank { LivePlatform.DOUYU.id }
                .equals(platform.ifBlank { LivePlatform.DOUYU.id }, ignoreCase = true)

    /** Player danmaku overlay; default on (documented in android-tv README). */
    fun isDanmakuEnabled(): Boolean = prefs.getBoolean(KEY_DANMAKU_ENABLED, true)

    fun setDanmakuEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DANMAKU_ENABLED, enabled).apply()
    }

    /** Font / opacity / scroll duration — desktop danmuSettings ranges. */
    fun getDanmakuStyle(): DanmakuStyle =
        DanmakuStyle.normalize(
            fontSizePx = prefs.getInt(KEY_DANMAKU_FONT_PX, DanmakuStyle.DEFAULT_FONT_PX),
            opacity = prefs.getFloat(KEY_DANMAKU_OPACITY, DanmakuStyle.DEFAULT_OPACITY),
            durationMs = prefs.getLong(KEY_DANMAKU_DURATION_MS, DanmakuStyle.DEFAULT_DURATION_MS),
        )

    fun setDanmakuStyle(style: DanmakuStyle) {
        val n = DanmakuStyle.normalize(style.fontSizePx, style.opacity, style.durationMs)
        prefs.edit()
            .putInt(KEY_DANMAKU_FONT_PX, n.fontSizePx)
            .putFloat(KEY_DANMAKU_OPACITY, n.opacity)
            .putLong(KEY_DANMAKU_DURATION_MS, n.durationMs)
            .apply()
    }

    fun cycleDanmakuFont(): DanmakuStyle {
        val cur = getDanmakuStyle()
        val next = cur.copy(fontSizePx = DanmakuStyleCycles.nextFont(cur.fontSizePx))
        setDanmakuStyle(next)
        return next
    }

    fun cycleDanmakuOpacity(): DanmakuStyle {
        val cur = getDanmakuStyle()
        val next = cur.copy(opacity = DanmakuStyleCycles.nextOpacity(cur.opacity))
        setDanmakuStyle(next)
        return next
    }

    fun cycleDanmakuDuration(): DanmakuStyle {
        val cur = getDanmakuStyle()
        val next = cur.copy(durationMs = DanmakuStyleCycles.nextDuration(cur.durationMs))
        setDanmakuStyle(next)
        return next
    }

    /** Wipe local follows (Settings optional action). */
    @Synchronized
    fun clearFollows() {
        prefs.edit().remove(KEY_FOLLOWS).apply()
    }

    /** Wipe recent watch history (Settings optional action). */
    @Synchronized
    fun clearRecent() {
        prefs.edit().remove(KEY_RECENT).apply()
    }

    fun followCount(): Int = loadFollows().size

    fun recentCount(): Int = loadRecent().size

    private fun readList(key: String): List<Streamer> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    parseStreamer(arr.optJSONObject(i))?.let { add(it) }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeList(key: String, items: List<Streamer>) {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun toJson(s: Streamer): JSONObject =
        JSONObject()
            .put("id", s.id)
            .put("platform", s.platform)
            .put("nickname", s.nickname)
            .put("title", s.title)
            .put("cover", s.cover)
            .put("avatar", s.avatar)
            .put("viewers", s.viewers)
            .put("categoryName", s.categoryName)
            .put("isLive", s.isLive)

    private fun parseStreamer(obj: JSONObject?): Streamer? {
        if (obj == null) return null
        val id = obj.optString("id")
        if (id.isBlank()) return null
        return Streamer(
            id = id,
            platform = obj.optString("platform", LivePlatform.DOUYU.id),
            nickname = obj.optString("nickname"),
            title = obj.optString("title"),
            cover = obj.optString("cover"),
            avatar = obj.optString("avatar"),
            viewers = obj.optLong("viewers"),
            categoryName = obj.optString("categoryName"),
            isLive = obj.optBoolean("isLive", true),
        )
    }

    companion object {
        private const val PREFS_NAME = "dtv_tv_library"
        private const val KEY_FOLLOWS = "follows"
        private const val KEY_RECENT = "recent"
        private const val KEY_DANMAKU_ENABLED = "danmaku_enabled"
        private const val KEY_DANMAKU_FONT_PX = "danmaku_font_px"
        private const val KEY_DANMAKU_OPACITY = "danmaku_opacity"
        private const val KEY_DANMAKU_DURATION_MS = "danmaku_duration_ms"
        const val MAX_FOLLOWS = 40
        const val MAX_RECENT = 24
    }
}

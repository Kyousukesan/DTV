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

    /** Upsert follow; newest first. Caps at [MAX_FOLLOWS]. */
    @Synchronized
    fun follow(streamer: Streamer) {
        val next = listOf(streamer.copy(platform = LivePlatform.DOUYU.id)) +
            loadFollows().filterNot { it.id == streamer.id }
        writeList(KEY_FOLLOWS, next.take(MAX_FOLLOWS))
    }

    @Synchronized
    fun unfollow(roomId: String) {
        writeList(KEY_FOLLOWS, loadFollows().filterNot { it.id == roomId })
    }

    fun toggleFollow(streamer: Streamer): Boolean {
        return if (isFollowed(streamer.id)) {
            unfollow(streamer.id)
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
        val next = listOf(streamer.copy(platform = LivePlatform.DOUYU.id)) +
            loadRecent().filterNot { it.id == streamer.id }
        writeList(KEY_RECENT, next.take(MAX_RECENT))
    }

    /** Replace a follow/recent card with fresher Douyu room meta (live flag, cover…). */
    @Synchronized
    fun updateCached(streamer: Streamer) {
        fun merge(list: List<Streamer>): List<Streamer> =
            list.map { if (it.id == streamer.id) streamer else it }
        writeList(KEY_FOLLOWS, merge(loadFollows()))
        writeList(KEY_RECENT, merge(loadRecent()))
    }

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
        const val MAX_FOLLOWS = 40
        const val MAX_RECENT = 24
    }
}

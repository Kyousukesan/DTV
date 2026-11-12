package com.dtv.androidtv.data.danmaku

import com.dtv.androidtv.data.model.DanmakuEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * One-room-at-a-time danmaku session (Douyu / Huya / Douyin / Bilibili).
 * Mirrors Go manager Start/Stop/RunningRoom semantics.
 */
interface DanmakuSession {
    val events: SharedFlow<DanmakuEvent>

    /** Start listening; stops any previous room first. */
    suspend fun start(roomId: String)

    /** Stop; non-empty [roomId] only stops that room (race-safe). */
    suspend fun stop(roomId: String = "")

    fun runningRoom(): String

    /** Non-suspend teardown for ViewModel.onCleared. */
    fun cancelAll()
}

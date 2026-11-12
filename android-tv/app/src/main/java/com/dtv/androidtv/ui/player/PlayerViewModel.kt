package com.dtv.androidtv.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dtv.androidtv.data.DtvApi
import com.dtv.androidtv.data.douyu.DouyuDanmakuManager
import com.dtv.androidtv.data.library.LibraryStore
import com.dtv.androidtv.data.model.DanmakuMessage
import com.dtv.androidtv.data.model.LivePlatform
import com.dtv.androidtv.data.model.StreamInfo
import com.dtv.androidtv.data.model.StreamVariant
import com.dtv.androidtv.data.model.Streamer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val loading: Boolean = true,
    val info: StreamInfo? = null,
    val selected: StreamVariant? = null,
    val error: String? = null,
    val notLive: Boolean = false,
    val useTestHls: Boolean = false,
    val followed: Boolean = false,
    val chromeVisible: Boolean = true,
    /** Overlay visibility; TCP session follows this for live Douyu rooms. */
    val danmakuEnabled: Boolean = true,
    /** Recent chat messages for the Compose overlay (ring buffer). */
    val danmakuMessages: List<DanmakuMessage> = emptyList(),
)

class PlayerViewModel(
    private val api: DtvApi,
    private val library: LibraryStore,
    private val platformId: String,
    private val roomId: String,
    private val fallbackNickname: String,
) : ViewModel() {
    private val platform = LivePlatform.fromId(platformId)
    private val danmakuEnabledForPlatform = platform == LivePlatform.DOUYU
    private val danmaku = if (danmakuEnabledForPlatform) {
        DouyuDanmakuManager(viewModelScope)
    } else {
        null
    }

    private val _state = MutableStateFlow(
        PlayerUiState(
            useTestHls = roomId == "test",
            followed = roomId != "test" && library.isFollowed(platformId, roomId),
            danmakuEnabled = danmakuEnabledForPlatform && library.isDanmakuEnabled(),
        ),
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    init {
        val manager = danmaku
        if (manager != null) {
            viewModelScope.launch {
                manager.events.collect { event ->
                    if (event.roomId != roomId && roomId != "test") return@collect
                    _state.update { ui ->
                        val next = (ui.danmakuMessages + event.message).takeLast(MAX_DANMAKU_BUFFER)
                        ui.copy(danmakuMessages = next)
                    }
                }
            }
        }
        if (roomId != "test") {
            load()
        } else {
            _state.update { it.copy(loading = false, useTestHls = true) }
        }
    }

    fun load() {
        if (roomId == "test") {
            _state.update {
                it.copy(loading = false, useTestHls = true, error = null, notLive = false)
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, notLive = false) }
            try {
                val info = api.getStreamInfo(roomId)
                library.recordRecent(info.toStreamer(fallbackNickname))
                if (!info.isLive) {
                    stopDanmakuSession()
                    _state.update {
                        it.copy(
                            loading = false,
                            info = info,
                            selected = null,
                            notLive = true,
                            error = null,
                            chromeVisible = true,
                            danmakuMessages = emptyList(),
                        )
                    }
                    return@launch
                }
                val selected = pickVariant(info.variants)
                    ?: throw IllegalStateException("没有可播放清晰度")
                _state.update {
                    it.copy(
                        loading = false,
                        info = info,
                        selected = selected,
                        error = null,
                        notLive = false,
                        followed = library.isFollowed(platformId, info.roomId),
                        danmakuMessages = emptyList(),
                    )
                }
                syncDanmakuSession()
            } catch (e: Exception) {
                stopDanmakuSession()
                _state.update {
                    it.copy(
                        loading = false,
                        error = e.message ?: "取流失败",
                        notLive = false,
                        chromeVisible = true,
                    )
                }
            }
        }
    }

    fun toggleFollow() {
        val info = _state.value.info
        val streamer = info?.toStreamer(fallbackNickname)
            ?: Streamer(
                id = roomId,
                platform = platform.id,
                nickname = fallbackNickname.ifBlank { "房间 $roomId" },
                title = "",
                cover = "",
                avatar = "",
                viewers = 0,
                categoryName = "",
                isLive = info?.isLive == true,
            )
        if (streamer.id.isBlank() || streamer.id == "test") return
        val nowFollowed = library.toggleFollow(
            streamer.copy(platform = streamer.platform.ifBlank { platform.id }),
        )
        _state.update { it.copy(followed = nowFollowed) }
    }

    fun setChromeVisible(visible: Boolean) {
        _state.update { it.copy(chromeVisible = visible) }
    }

    fun toggleChrome() {
        _state.update { it.copy(chromeVisible = !it.chromeVisible) }
    }

    /** DPAD chrome toggle: Douyu-only; persists preference and starts/stops TCP. */
    fun toggleDanmaku() {
        if (!danmakuEnabledForPlatform) return
        val next = !_state.value.danmakuEnabled
        library.setDanmakuEnabled(next)
        _state.update {
            it.copy(
                danmakuEnabled = next,
                danmakuMessages = if (next) it.danmakuMessages else emptyList(),
            )
        }
        viewModelScope.launch { syncDanmakuSession() }
    }

    suspend fun resolveFreshUrl(quality: String, line: String): String =
        api.getStreamUrl(roomId, quality, line)

    fun reportPlaybackError(message: String) {
        _state.update { it.copy(error = message, chromeVisible = true) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    override fun onCleared() {
        danmaku?.cancelAll()
        super.onCleared()
    }

    private suspend fun syncDanmakuSession() {
        val manager = danmaku ?: return
        if (roomId == "test") return
        val ui = _state.value
        val live = ui.info?.isLive == true && !ui.notLive && ui.error == null
        if (ui.danmakuEnabled && live) {
            manager.start(roomId)
        } else {
            stopDanmakuSession()
        }
    }

    private suspend fun stopDanmakuSession() {
        danmaku?.stop(roomId)
    }

    private fun pickVariant(variants: List<StreamVariant>): StreamVariant? {
        if (variants.isEmpty()) return null
        // Prefer mid-ladder over 原画 for anonymous sessions (often gated).
        return variants.firstOrNull { it.id != "0" } ?: variants.first()
    }

    private fun StreamInfo.toStreamer(fallbackNick: String): Streamer =
        Streamer(
            id = roomId,
            platform = this.platform.ifBlank { platformId },
            nickname = nickname.ifBlank { fallbackNick }.ifBlank { "房间 $roomId" },
            title = title,
            cover = "",
            avatar = avatar,
            viewers = 0,
            categoryName = "",
            isLive = isLive,
        )

    class Factory(
        private val api: DtvApi,
        private val library: LibraryStore,
        private val platformId: String,
        private val roomId: String,
        private val fallbackNickname: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(api, library, platformId, roomId, fallbackNickname) as T
    }

    companion object {
        private const val MAX_DANMAKU_BUFFER = 80
    }
}

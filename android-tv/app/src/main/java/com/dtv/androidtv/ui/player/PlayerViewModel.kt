package com.dtv.androidtv.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dtv.androidtv.DtvTvApp
import com.dtv.androidtv.data.DtvApi
import com.dtv.androidtv.data.PlatformCatalog
import com.dtv.androidtv.data.danmaku.DanmakuSessions
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
    /** Active CDN line id (empty when platform has no lines). */
    val selectedLineId: String = "",
    val error: String? = null,
    val notLive: Boolean = false,
    val useTestHls: Boolean = false,
    val followed: Boolean = false,
    val chromeVisible: Boolean = true,
    /** Overlay on; WS/TCP session follows when live. */
    val danmakuEnabled: Boolean = true,
    /** Platform has a danmaku backend (all four live platforms). */
    val danmakuSupported: Boolean = false,
    val danmakuMessages: List<DanmakuMessage> = emptyList(),
    /** True while switching line (re-fetch StreamInfo) without leaving the room. */
    val switching: Boolean = false,
) {
    val canCycleQuality: Boolean get() = (info?.variants?.size ?: 0) > 1
    val canCycleLine: Boolean get() = (info?.lines?.size ?: 0) > 1
    val lineLabel: String?
        get() {
            val lines = info?.lines.orEmpty()
            if (lines.isEmpty()) return null
            return lines.firstOrNull { it.id == selectedLineId }?.label
                ?: lines.firstOrNull()?.label
        }
}

class PlayerViewModel(
    private val api: DtvApi,
    private val library: LibraryStore,
    private val catalog: PlatformCatalog,
    private val platformId: String,
    private val roomId: String,
    private val fallbackNickname: String,
) : ViewModel() {
    private val platform = LivePlatform.fromId(platformId)
    private val danmaku = if (roomId != "test") {
        DanmakuSessions.create(platform, viewModelScope, catalog)
    } else {
        null
    }
    private val danmakuSupported = danmaku != null

    private val _state = MutableStateFlow(
        PlayerUiState(
            useTestHls = roomId == "test",
            followed = roomId != "test" && library.isFollowed(platformId, roomId),
            danmakuEnabled = danmakuSupported && library.isDanmakuEnabled(),
            danmakuSupported = danmakuSupported,
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
                val preferredQuality = _state.value.selected?.id.orEmpty()
                val selected = pickVariant(info.variants, preferredQuality)
                    ?: throw IllegalStateException("没有可播放清晰度")
                val lineId = resolveLineId(info, _state.value.selectedLineId)
                _state.update {
                    it.copy(
                        loading = false,
                        switching = false,
                        info = info,
                        selected = selected,
                        selectedLineId = lineId,
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
                        switching = false,
                        error = e.message ?: "取流失败",
                        notLive = false,
                        chromeVisible = true,
                    )
                }
            }
        }
    }

    /**
     * Cycle clarity using URLs already on [StreamInfo] (desktop quality switch).
     * Reloads Media3 via [PlayerUiState.selected] change; stays in the room.
     */
    fun cycleQuality() {
        val ui = _state.value
        val variants = ui.info?.variants.orEmpty()
        if (variants.size <= 1) return
        val currentId = ui.selected?.id
        val idx = variants.indexOfFirst { it.id == currentId }.let { if (it < 0) 0 else it }
        val next = variants[(idx + 1) % variants.size]
        if (next.id == currentId && next.url == ui.selected?.url) return
        _state.update {
            it.copy(
                selected = next,
                chromeVisible = true,
                error = null,
            )
        }
    }

    /**
     * Cycle CDN line: re-fetch [DtvApi.getStreamInfo] with the next line id
     * (desktop line switch), then pick the same quality when still present.
     */
    fun cycleLine() {
        val ui = _state.value
        val lines = ui.info?.lines.orEmpty()
        if (lines.size <= 1 || roomId == "test") return
        val current = ui.selectedLineId
        val idx = lines.indexOfFirst { it.id == current }.let { if (it < 0) 0 else it }
        val nextLine = lines[(idx + 1) % lines.size]
        if (nextLine.id == current && ui.switching) return
        val keepQuality = ui.selected?.id.orEmpty()
        viewModelScope.launch {
            _state.update {
                it.copy(
                    switching = true,
                    chromeVisible = true,
                    error = null,
                    selectedLineId = nextLine.id,
                )
            }
            try {
                val info = api.getStreamInfo(roomId, nextLine.id)
                if (!info.isLive) {
                    stopDanmakuSession()
                    _state.update {
                        it.copy(
                            switching = false,
                            loading = false,
                            info = info,
                            selected = null,
                            notLive = true,
                            chromeVisible = true,
                        )
                    }
                    return@launch
                }
                val selected = pickVariant(info.variants, keepQuality)
                    ?: throw IllegalStateException("没有可播放清晰度")
                val lineId = resolveLineId(info, nextLine.id)
                _state.update {
                    it.copy(
                        switching = false,
                        info = info,
                        selected = selected,
                        selectedLineId = lineId,
                        notLive = false,
                        error = null,
                        followed = library.isFollowed(platformId, info.roomId),
                    )
                }
                syncDanmakuSession()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        switching = false,
                        error = e.message ?: "切换线路失败",
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

    /** DPAD chrome toggle; persists preference and starts/stops the platform session. */
    fun toggleDanmaku() {
        if (!danmakuSupported) return
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

    private fun pickVariant(
        variants: List<StreamVariant>,
        preferredId: String = "",
    ): StreamVariant? {
        if (variants.isEmpty()) return null
        if (preferredId.isNotBlank()) {
            variants.firstOrNull { it.id == preferredId }?.let { return it }
        }
        return variants.firstOrNull { it.id != "0" } ?: variants.first()
    }

    private fun resolveLineId(info: StreamInfo, preferred: String): String {
        if (info.lines.isEmpty()) return ""
        if (preferred.isNotBlank() && info.lines.any { it.id == preferred }) return preferred
        return info.lines.first().id
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
        private val catalog: PlatformCatalog,
        private val platformId: String,
        private val roomId: String,
        private val fallbackNickname: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(api, library, catalog, platformId, roomId, fallbackNickname) as T
    }

    companion object {
        private const val MAX_DANMAKU_BUFFER = 80
    }
}

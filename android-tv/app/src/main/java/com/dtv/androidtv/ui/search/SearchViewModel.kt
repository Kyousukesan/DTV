package com.dtv.androidtv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dtv.androidtv.data.PlatformCatalog
import com.dtv.androidtv.data.model.LivePlatform
import com.dtv.androidtv.data.model.SearchHit
import com.dtv.androidtv.data.model.Streamer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val platform: LivePlatform = LivePlatform.DOUYU,
    val query: String = "",
    val loading: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    val error: String? = null,
    val hint: String = "输入主播昵称或房间号",
)

class SearchViewModel(
    private val catalog: PlatformCatalog,
) : ViewModel() {
    private val _state = MutableStateFlow(
        SearchUiState(
            platform = catalog.active,
            hint = hintFor(catalog.active),
        ),
    )
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun setPlatform(platform: LivePlatform) {
        catalog.active = platform
        _state.update {
            it.copy(
                platform = platform,
                hits = emptyList(),
                error = null,
                hint = hintFor(platform),
            )
        }
        if (_state.value.query.isNotBlank()) {
            search(_state.value.query)
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun search(raw: String = _state.value.query) {
        val keyword = raw.trim()
        _state.update { it.copy(query = keyword) }
        searchJob?.cancel()
        if (keyword.isEmpty()) {
            _state.update { it.copy(loading = false, hits = emptyList(), error = null) }
            return
        }
        val platform = _state.value.platform
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val hits = catalog.api(platform).search(keyword)
                _state.update {
                    it.copy(
                        loading = false,
                        hits = hits,
                        error = if (hits.isEmpty()) emptyMessage(platform) else null,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        hits = emptyList(),
                        error = e.message ?: "搜索失败",
                    )
                }
            }
        }
    }

    fun toStreamer(hit: SearchHit): Streamer =
        Streamer(
            id = hit.id,
            platform = _state.value.platform.id,
            nickname = hit.nickname,
            title = hit.title,
            cover = "",
            avatar = hit.avatar,
            viewers = 0L,
            categoryName = "",
            isLive = hit.isLive,
        )

    class Factory(
        private val catalog: PlatformCatalog,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(catalog) as T
    }

    companion object {
        fun hintFor(platform: LivePlatform): String =
            when (platform) {
                LivePlatform.DOUYIN -> "抖音仅支持房间号 / 直播间链接"
                LivePlatform.DOUYU -> "搜索斗鱼主播昵称或房间号"
                LivePlatform.HUYA -> "搜索虎牙主播昵称"
                LivePlatform.BILIBILI -> "搜索 B 站直播间"
            }

        private fun emptyMessage(platform: LivePlatform): String =
            when (platform) {
                LivePlatform.DOUYIN -> "未找到该房间（请确认房间号）"
                else -> "没有匹配结果"
            }
    }
}

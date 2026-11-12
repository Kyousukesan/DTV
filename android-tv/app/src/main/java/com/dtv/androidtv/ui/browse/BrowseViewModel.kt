package com.dtv.androidtv.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dtv.androidtv.data.DtvApi
import com.dtv.androidtv.data.douyu.DouyuConstants
import com.dtv.androidtv.data.library.LibraryStore
import com.dtv.androidtv.data.model.Category
import com.dtv.androidtv.data.model.Streamer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val refreshingLibrary: Boolean = false,
    val follows: List<Streamer> = emptyList(),
    val recent: List<Streamer> = emptyList(),
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String = DouyuConstants.DEFAULT_CATE2_ID,
    val selectedCategoryName: String = DouyuConstants.DEFAULT_CATE2_NAME,
    val rooms: List<Streamer> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val error: String? = null,
    /** Per-row focus memory: last focused card index. */
    val focusFollows: Int = 0,
    val focusRecent: Int = 0,
    val focusLive: Int = 0,
    val focusCategory: Int = 0,
)

class BrowseViewModel(
    private val api: DtvApi,
    private val library: LibraryStore,
) : ViewModel() {
    private val _state = MutableStateFlow(
        BrowseUiState(
            follows = library.loadFollows(),
            recent = library.loadRecent(),
        ),
    )
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Reload categories + live list and refresh follow/recent live flags from Douyu. */
    fun refresh() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    follows = library.loadFollows(),
                    recent = library.loadRecent(),
                )
            }
            try {
                val groups = api.listCategories()
                val flat = groups.flatMap { it.categories }
                val preferred = flat.firstOrNull { it.id == DouyuConstants.DEFAULT_CATE2_ID }
                    ?: flat.firstOrNull()
                val cateId = preferred?.id ?: DouyuConstants.DEFAULT_CATE2_ID
                val cateName = preferred?.name ?: DouyuConstants.DEFAULT_CATE2_NAME
                val cateIndex = flat.indexOfFirst { it.id == cateId }.coerceAtLeast(0)
                val page = api.listStreamers(cateId, page = 1)
                _state.update {
                    it.copy(
                        loading = false,
                        categories = flat.take(24),
                        selectedCategoryId = cateId,
                        selectedCategoryName = cateName,
                        focusCategory = cateIndex.coerceAtMost(23),
                        rooms = page.items,
                        page = page.page,
                        hasMore = page.hasMore,
                        error = null,
                    )
                }
                refreshLibraryMeta()
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: "加载失败")
                }
            }
        }
    }

    /** Soft-refresh follow/recent cards against Douyu betard (best-effort). */
    fun refreshLibraryMeta() {
        viewModelScope.launch {
            _state.update { it.copy(refreshingLibrary = true) }
            val ids = (library.loadFollows() + library.loadRecent())
                .map { it.id }
                .distinct()
            if (ids.isEmpty()) {
                _state.update {
                    it.copy(
                        refreshingLibrary = false,
                        follows = library.loadFollows(),
                        recent = library.loadRecent(),
                    )
                }
                return@launch
            }
            coroutineScope {
                ids.map { id ->
                    async {
                        runCatching {
                            val fresh = api.getRoomInfo(id)
                            library.updateCached(fresh)
                        }
                    }
                }.awaitAll()
            }
            _state.update {
                it.copy(
                    refreshingLibrary = false,
                    follows = library.loadFollows(),
                    recent = library.loadRecent(),
                )
            }
        }
    }

    fun reloadLibraryRows() {
        _state.update {
            it.copy(
                follows = library.loadFollows(),
                recent = library.loadRecent(),
            )
        }
    }

    fun selectCategory(category: Category, categoryIndex: Int) {
        if (category.id == _state.value.selectedCategoryId && _state.value.rooms.isNotEmpty()) {
            _state.update { it.copy(focusCategory = categoryIndex) }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    selectedCategoryId = category.id,
                    selectedCategoryName = category.name,
                    focusCategory = categoryIndex,
                    focusLive = 0,
                    rooms = emptyList(),
                )
            }
            try {
                val page = api.listStreamers(category.id, page = 1)
                _state.update {
                    it.copy(
                        loading = false,
                        rooms = page.items,
                        page = page.page,
                        hasMore = page.hasMore,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    fun loadMore() {
        val snap = _state.value
        if (!snap.hasMore || snap.loadingMore || snap.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, error = null) }
            try {
                val next = api.listStreamers(snap.selectedCategoryId, page = snap.page + 1)
                _state.update {
                    it.copy(
                        loadingMore = false,
                        rooms = it.rooms + next.items,
                        page = next.page,
                        hasMore = next.hasMore,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loadingMore = false, error = e.message ?: "加载更多失败")
                }
            }
        }
    }

    fun setFocusFollows(index: Int) = _state.update { it.copy(focusFollows = index) }
    fun setFocusRecent(index: Int) = _state.update { it.copy(focusRecent = index) }
    fun setFocusLive(index: Int) = _state.update { it.copy(focusLive = index) }
    fun setFocusCategory(index: Int) = _state.update { it.copy(focusCategory = index) }

    fun openRoom(streamer: Streamer) {
        library.recordRecent(streamer)
        reloadLibraryRows()
    }

    class Factory(
        private val api: DtvApi,
        private val library: LibraryStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BrowseViewModel(api, library) as T
    }
}

package com.dtv.androidtv.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dtv.androidtv.data.PlatformCatalog
import com.dtv.androidtv.data.library.LibraryStore
import com.dtv.androidtv.data.model.Category
import com.dtv.androidtv.data.model.LivePlatform
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
    val platform: LivePlatform = LivePlatform.DOUYU,
    val follows: List<Streamer> = emptyList(),
    val recent: List<Streamer> = emptyList(),
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String = "",
    val selectedCategoryName: String = "",
    val rooms: List<Streamer> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val error: String? = null,
    val focusFollows: Int = 0,
    val focusRecent: Int = 0,
    val focusLive: Int = 0,
    val focusCategory: Int = 0,
    val focusPlatform: Int = 0,
)

class BrowseViewModel(
    private val catalog: PlatformCatalog,
    private val library: LibraryStore,
) : ViewModel() {
    private val _state = MutableStateFlow(
        BrowseUiState(
            platform = catalog.active,
            follows = library.loadFollows(),
            recent = library.loadRecent(),
            focusPlatform = LivePlatform.entries.indexOf(catalog.active).coerceAtLeast(0),
        ),
    )
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun selectPlatform(platform: LivePlatform, index: Int) {
        if (platform == _state.value.platform && _state.value.rooms.isNotEmpty()) {
            _state.update { it.copy(focusPlatform = index) }
            return
        }
        catalog.active = platform
        _state.update {
            it.copy(
                platform = platform,
                focusPlatform = index,
                focusCategory = 0,
                focusLive = 0,
                selectedCategoryId = "",
                selectedCategoryName = "",
                rooms = emptyList(),
                categories = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    /** Reload categories + live list for the active platform; refresh library meta. */
    fun refresh() {
        viewModelScope.launch {
            val platform = catalog.active
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    platform = platform,
                    follows = library.loadFollows(),
                    recent = library.loadRecent(),
                )
            }
            try {
                val api = catalog.api(platform)
                val groups = api.listCategories()
                val flat = groups.flatMap { it.categories }
                val preferredId = catalog.defaultCategoryId(platform)
                val preferred = flat.firstOrNull { it.id == preferredId } ?: flat.firstOrNull()
                val cateId = preferred?.id.orEmpty()
                val cateName = preferred?.name.orEmpty()
                val cateIndex = flat.indexOfFirst { it.id == cateId }.coerceAtLeast(0)
                val page = if (cateId.isNotBlank()) {
                    api.listStreamers(cateId, page = 1)
                } else {
                    com.dtv.androidtv.data.model.StreamerPage(emptyList(), 1, false)
                }
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

    /** Soft-refresh follow/recent cards against each item's platform API. */
    fun refreshLibraryMeta() {
        viewModelScope.launch {
            _state.update { it.copy(refreshingLibrary = true) }
            val items = (library.loadFollows() + library.loadRecent())
                .distinctBy { "${it.platform}:${it.id}" }
            if (items.isEmpty()) {
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
                items.map { card ->
                    async {
                        runCatching {
                            val fresh = catalog.apiFor(card.platform).getRoomInfo(card.id)
                            library.updateCached(
                                fresh.copy(
                                    platform = fresh.platform.ifBlank { card.platform },
                                ),
                            )
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
                val page = catalog.api().listStreamers(category.id, page = 1)
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
                val next = catalog.api().listStreamers(snap.selectedCategoryId, page = snap.page + 1)
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
    fun setFocusPlatform(index: Int) = _state.update { it.copy(focusPlatform = index) }

    fun openRoom(streamer: Streamer) {
        library.recordRecent(streamer)
        reloadLibraryRows()
    }

    class Factory(
        private val catalog: PlatformCatalog,
        private val library: LibraryStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BrowseViewModel(catalog, library) as T
    }
}

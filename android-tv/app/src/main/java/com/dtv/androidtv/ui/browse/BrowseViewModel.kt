package com.dtv.androidtv.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dtv.androidtv.data.PlatformCatalog
import com.dtv.androidtv.data.library.LibraryStore
import com.dtv.androidtv.data.model.Category
import com.dtv.androidtv.data.model.CategoryGroup
import com.dtv.androidtv.data.model.LivePlatform
import com.dtv.androidtv.data.model.Streamer
import com.dtv.androidtv.data.model.StreamerPage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Home browse state aligned with desktop [HomeScreen]:
 * platform → cate1 group → cate2 category → paged streamer grid.
 */
data class BrowseUiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val refreshingLibrary: Boolean = false,
    val platform: LivePlatform = LivePlatform.DOUYU,
    val follows: List<Streamer> = emptyList(),
    val recent: List<Streamer> = emptyList(),
    /** cate1 groups (desktop CategoryBar). */
    val groups: List<CategoryGroup> = emptyList(),
    val selectedGroupId: String = "",
    val selectedGroupName: String = "",
    /** cate2 list for the active group. */
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
    val focusGroup: Int = 0,
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
                focusGroup = 0,
                focusCategory = 0,
                focusLive = 0,
                selectedGroupId = "",
                selectedGroupName = "",
                selectedCategoryId = "",
                selectedCategoryName = "",
                rooms = emptyList(),
                groups = emptyList(),
                categories = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    /** Reload category tree + live list for the active platform. */
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
                val preferredId = catalog.defaultCategoryId(platform)
                val pick = pickInitialSelection(groups, preferredId)
                val page = if (pick.categoryId.isNotBlank()) {
                    api.listStreamers(pick.categoryId, page = 1)
                } else {
                    StreamerPage(emptyList(), 1, false)
                }
                _state.update {
                    it.copy(
                        loading = false,
                        groups = groups,
                        selectedGroupId = pick.groupId,
                        selectedGroupName = pick.groupName,
                        categories = pick.categories,
                        selectedCategoryId = pick.categoryId,
                        selectedCategoryName = pick.categoryName,
                        focusGroup = pick.groupIndex,
                        focusCategory = pick.categoryIndex,
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

    /** Desktop: switching cate1 auto-selects its first cate2. */
    fun selectGroup(group: CategoryGroup, groupIndex: Int) {
        if (group.id == _state.value.selectedGroupId && _state.value.categories.isNotEmpty()) {
            _state.update { it.copy(focusGroup = groupIndex) }
            return
        }
        val first = group.categories.firstOrNull()
        if (first == null) {
            _state.update {
                it.copy(
                    selectedGroupId = group.id,
                    selectedGroupName = group.name,
                    categories = emptyList(),
                    selectedCategoryId = "",
                    selectedCategoryName = "",
                    focusGroup = groupIndex,
                    focusCategory = 0,
                    rooms = emptyList(),
                    hasMore = false,
                )
            }
            return
        }
        selectCategory(first, 0, group, groupIndex)
    }

    fun selectCategory(
        category: Category,
        categoryIndex: Int,
        group: CategoryGroup? = null,
        groupIndex: Int? = null,
    ) {
        val snap = _state.value
        val activeGroup = group
            ?: snap.groups.firstOrNull { it.id == snap.selectedGroupId }
            ?: snap.groups.firstOrNull()
        if (
            category.id == snap.selectedCategoryId &&
            snap.rooms.isNotEmpty() &&
            group == null
        ) {
            _state.update { it.copy(focusCategory = categoryIndex) }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    selectedGroupId = activeGroup?.id.orEmpty(),
                    selectedGroupName = activeGroup?.name.orEmpty(),
                    categories = activeGroup?.categories.orEmpty(),
                    selectedCategoryId = category.id,
                    selectedCategoryName = category.name,
                    focusGroup = groupIndex ?: it.focusGroup,
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
        if (snap.selectedCategoryId.isBlank()) return
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
    fun setFocusGroup(index: Int) = _state.update { it.copy(focusGroup = index) }
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

    companion object {
        data class InitialPick(
            val groupId: String,
            val groupName: String,
            val groupIndex: Int,
            val categories: List<Category>,
            val categoryId: String,
            val categoryName: String,
            val categoryIndex: Int,
        )

        /** Prefer [preferredCategoryId] anywhere in the tree; else first cate2. */
        fun pickInitialSelection(
            groups: List<CategoryGroup>,
            preferredCategoryId: String,
        ): InitialPick {
            if (groups.isEmpty()) {
                return InitialPick("", "", 0, emptyList(), "", "", 0)
            }
            if (preferredCategoryId.isNotBlank()) {
                groups.forEachIndexed { gi, g ->
                    val ci = g.categories.indexOfFirst { it.id == preferredCategoryId }
                    if (ci >= 0) {
                        val c = g.categories[ci]
                        return InitialPick(
                            groupId = g.id,
                            groupName = g.name,
                            groupIndex = gi,
                            categories = g.categories,
                            categoryId = c.id,
                            categoryName = c.name,
                            categoryIndex = ci,
                        )
                    }
                }
            }
            val g = groups.first()
            val c = g.categories.firstOrNull()
            return InitialPick(
                groupId = g.id,
                groupName = g.name,
                groupIndex = 0,
                categories = g.categories,
                categoryId = c?.id.orEmpty(),
                categoryName = c?.name.orEmpty(),
                categoryIndex = 0,
            )
        }
    }
}

package com.dtv.androidtv.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dtv.androidtv.data.DtvApi
import com.dtv.androidtv.data.douyu.DouyuConstants
import com.dtv.androidtv.data.model.Category
import com.dtv.androidtv.data.model.Streamer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String = DouyuConstants.DEFAULT_CATE2_ID,
    val selectedCategoryName: String = DouyuConstants.DEFAULT_CATE2_NAME,
    val rooms: List<Streamer> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val error: String? = null,
)

class BrowseViewModel(
    private val api: DtvApi,
) : ViewModel() {
    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val groups = api.listCategories()
                val flat = groups.flatMap { it.categories }
                val preferred = flat.firstOrNull { it.id == DouyuConstants.DEFAULT_CATE2_ID }
                    ?: flat.firstOrNull()
                val cateId = preferred?.id ?: DouyuConstants.DEFAULT_CATE2_ID
                val cateName = preferred?.name ?: DouyuConstants.DEFAULT_CATE2_NAME
                val page = api.listStreamers(cateId, page = 1)
                _state.update {
                    it.copy(
                        loading = false,
                        categories = flat.take(24),
                        selectedCategoryId = cateId,
                        selectedCategoryName = cateName,
                        rooms = page.items,
                        page = page.page,
                        hasMore = page.hasMore,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: "加载失败")
                }
            }
        }
    }

    fun selectCategory(category: Category) {
        if (category.id == _state.value.selectedCategoryId && _state.value.rooms.isNotEmpty()) {
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    selectedCategoryId = category.id,
                    selectedCategoryName = category.name,
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

    class Factory(private val api: DtvApi) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BrowseViewModel(api) as T
    }
}

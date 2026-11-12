package com.dtv.androidtv.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dtv.androidtv.DtvTvApp
import com.dtv.androidtv.data.PlatformCatalog
import com.dtv.androidtv.data.model.Category
import com.dtv.androidtv.data.model.LivePlatform
import com.dtv.androidtv.data.model.Streamer
import com.dtv.androidtv.ui.TvActionButton
import com.dtv.androidtv.ui.TvLayout
import com.dtv.androidtv.ui.TvScreenBackground
import com.dtv.androidtv.ui.TvStatusBlock
import com.dtv.androidtv.ui.components.CategoryChip
import com.dtv.androidtv.ui.components.RoomRow

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseScreen(
    catalog: PlatformCatalog,
    onOpenRoom: (Streamer) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    viewModel: BrowseViewModel = viewModel(
        factory = BrowseViewModel.Factory(catalog, DtvTvApp.instance.libraryStore),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Returning from player: reload library rows so recent/follows stay current.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reloadLibraryRows()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    TvScreenBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(TvLayout.ScreenPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DTV",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "${catalog.label(state.platform)} · 关注 / 最近 / ${state.selectedCategoryName}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvActionButton(label = "搜索", onClick = onOpenSearch, emphasized = true)
                    TvActionButton(label = "设置", onClick = onOpenSettings)
                }
            }

            Spacer(Modifier.height(TvLayout.HeaderBottom))
            PlatformRow(
                selected = state.platform,
                focusedIndex = state.focusPlatform,
                onFocusedIndex = viewModel::setFocusPlatform,
                labelOf = catalog::label,
                onClick = { platform, index -> viewModel.selectPlatform(platform, index) },
            )
            Spacer(Modifier.height(14.dp))

            when {
                state.loading && state.rooms.isEmpty() && state.follows.isEmpty() && state.recent.isEmpty() -> {
                    TvStatusBlock(title = "正在加载直播分区…")
                }
                state.error != null && state.rooms.isEmpty() && state.follows.isEmpty() && state.recent.isEmpty() -> {
                    TvStatusBlock(
                        title = "加载失败",
                        message = state.error,
                        actionLabel = "重试",
                        onAction = viewModel::refresh,
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(TvLayout.RowSpacing),
                        contentPadding = PaddingValues(bottom = 28.dp),
                    ) {
                        item(key = "follows") {
                            RoomRow(
                                title = "关注",
                                rooms = state.follows,
                                focusedIndex = state.focusFollows,
                                onFocusedIndex = viewModel::setFocusFollows,
                                onOpenRoom = { room ->
                                    viewModel.openRoom(room)
                                    onOpenRoom(room)
                                },
                                emptyHint = "播放页选择「关注本房间」后会出现在这里",
                            )
                        }
                        item(key = "recent") {
                            RoomRow(
                                title = "最近观看",
                                rooms = state.recent,
                                focusedIndex = state.focusRecent,
                                onFocusedIndex = viewModel::setFocusRecent,
                                onOpenRoom = { room ->
                                    viewModel.openRoom(room)
                                    onOpenRoom(room)
                                },
                                emptyHint = "打开过的直播间会出现在这里",
                            )
                        }
                        item(key = "categories") {
                            CategoryRow(
                                categories = state.categories,
                                selectedId = state.selectedCategoryId,
                                focusedIndex = state.focusCategory,
                                onFocusedIndex = viewModel::setFocusCategory,
                                onClick = { category, index ->
                                    viewModel.selectCategory(category, index)
                                },
                            )
                        }
                        item(key = "live") {
                            if (state.loading && state.rooms.isEmpty()) {
                                Text(
                                    "加载分区直播…",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                )
                            } else {
                                RoomRow(
                                    title = "直播 · ${state.selectedCategoryName}",
                                    rooms = state.rooms,
                                    focusedIndex = state.focusLive,
                                    onFocusedIndex = { index ->
                                        viewModel.setFocusLive(index)
                                        if (index >= state.rooms.size - 4) {
                                            viewModel.loadMore()
                                        }
                                    },
                                    onOpenRoom = { room ->
                                        viewModel.openRoom(room)
                                        onOpenRoom(room)
                                    },
                                    emptyHint = state.error ?: "该分区暂无直播，试试其他分区或平台",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun PlatformRow(
    selected: LivePlatform,
    focusedIndex: Int,
    onFocusedIndex: (Int) -> Unit,
    labelOf: (LivePlatform) -> String,
    onClick: (LivePlatform, Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "平台",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = TvLayout.SectionTitleBottom),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = TvLayout.RowContentPadding,
            modifier = Modifier.focusRestorer(),
        ) {
            itemsIndexed(LivePlatform.entries, key = { _, p -> p.id }) { index, platform ->
                CategoryChip(
                    label = labelOf(platform),
                    selected = platform == selected,
                    onClick = {
                        onFocusedIndex(index)
                        onClick(platform, index)
                    },
                    modifier = Modifier.onFocusChanged { focus ->
                        if (focus.isFocused) onFocusedIndex(index)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
@Suppress("UNUSED_PARAMETER")
private fun CategoryRow(
    categories: List<Category>,
    selectedId: String,
    focusedIndex: Int,
    onFocusedIndex: (Int) -> Unit,
    onClick: (Category, Int) -> Unit,
) {
    if (categories.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "分区",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = TvLayout.SectionTitleBottom),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = TvLayout.RowContentPadding,
            modifier = Modifier.focusRestorer(),
        ) {
            itemsIndexed(categories, key = { _, c -> c.id }) { index, category ->
                CategoryChip(
                    label = category.name,
                    selected = category.id == selectedId,
                    onClick = {
                        onFocusedIndex(index)
                        onClick(category, index)
                    },
                    modifier = Modifier.onFocusChanged { state ->
                        if (state.isFocused) onFocusedIndex(index)
                    },
                )
            }
        }
    }
}

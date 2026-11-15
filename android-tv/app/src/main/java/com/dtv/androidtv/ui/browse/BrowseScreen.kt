package com.dtv.androidtv.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.dtv.androidtv.data.model.CategoryGroup
import com.dtv.androidtv.data.model.LivePlatform
import com.dtv.androidtv.data.model.Streamer
import com.dtv.androidtv.ui.TvActionButton
import com.dtv.androidtv.ui.TvLayout
import com.dtv.androidtv.ui.TvScreenBackground
import com.dtv.androidtv.ui.TvStatusBlock
import com.dtv.androidtv.ui.components.CategoryChip
import com.dtv.androidtv.ui.components.RoomRow
import com.dtv.androidtv.ui.components.StreamerGrid

/**
 * TV home browse mirroring desktop [HomeScreen]:
 * platform → 关注/最近 → cate1+cate2 CategoryBar → StreamerGrid.
 */
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

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reloadLibraryRows()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Prefetch next page when DPAD focus nears the end of the grid.
    LaunchedEffect(state.focusLive, state.rooms.size, state.hasMore) {
        if (state.hasMore && state.rooms.isNotEmpty() && state.focusLive >= state.rooms.size - GRID_COLUMNS) {
            viewModel.loadMore()
        }
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
                    val subtitle = buildString {
                        append(catalog.label(state.platform))
                        if (state.selectedGroupName.isNotBlank()) {
                            append(" · ").append(state.selectedGroupName)
                        }
                        if (state.selectedCategoryName.isNotBlank()) {
                            append(" / ").append(state.selectedCategoryName)
                        }
                    }
                    Text(
                        text = subtitle,
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
            Spacer(Modifier.height(12.dp))

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
                    // Compact library shelves (TV-only; desktop keeps these elsewhere).
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
                        cardWidth = 200.dp,
                    )
                    Spacer(Modifier.height(TvLayout.RowSpacing))
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
                        cardWidth = 200.dp,
                    )
                    Spacer(Modifier.height(TvLayout.RowSpacing))

                    CategoryBar(
                        groups = state.groups,
                        selectedGroupId = state.selectedGroupId,
                        categories = state.categories,
                        selectedCategoryId = state.selectedCategoryId,
                        focusGroup = state.focusGroup,
                        focusCategory = state.focusCategory,
                        onFocusGroup = viewModel::setFocusGroup,
                        onFocusCategory = viewModel::setFocusCategory,
                        onSelectGroup = { group, index -> viewModel.selectGroup(group, index) },
                        onSelectCategory = { category, index ->
                            viewModel.selectCategory(category, index)
                        },
                    )
                    Spacer(Modifier.height(10.dp))

                    when {
                        state.loading && state.rooms.isEmpty() -> {
                            Text(
                                "加载分区直播…",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            )
                        }
                        else -> {
                            Text(
                                text = "直播 · ${state.selectedCategoryName.ifBlank { "全部" }}",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(bottom = TvLayout.SectionTitleBottom),
                            )
                            StreamerGrid(
                                rooms = state.rooms,
                                focusedIndex = state.focusLive,
                                onFocusedIndex = viewModel::setFocusLive,
                                onOpenRoom = { room ->
                                    viewModel.openRoom(room)
                                    onOpenRoom(room)
                                },
                                columns = GRID_COLUMNS,
                                loadingMore = state.loadingMore,
                                emptyHint = state.error
                                    ?: "这个分区暂时没有主播在播",
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Desktop StreamerGrid density target for 1080p TV. */
private const val GRID_COLUMNS = 4

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
@Suppress("UNUSED_PARAMETER")
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

/**
 * Two-row category bar matching desktop CategoryBar (cate1 tabs + cate2 chips).
 * Douyu L3 skipped — TV API has no listSubCategories yet.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
@Suppress("UNUSED_PARAMETER")
private fun CategoryBar(
    groups: List<CategoryGroup>,
    selectedGroupId: String,
    categories: List<Category>,
    selectedCategoryId: String,
    focusGroup: Int,
    focusCategory: Int,
    onFocusGroup: (Int) -> Unit,
    onFocusCategory: (Int) -> Unit,
    onSelectGroup: (CategoryGroup, Int) -> Unit,
    onSelectCategory: (Category, Int) -> Unit,
) {
    if (groups.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "一级分区",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = TvLayout.SectionTitleBottom),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = TvLayout.RowContentPadding,
            modifier = Modifier.focusRestorer(),
        ) {
            itemsIndexed(groups, key = { _, g -> g.id }) { index, group ->
                CategoryChip(
                    label = group.name,
                    selected = group.id == selectedGroupId,
                    onClick = {
                        onFocusGroup(index)
                        onSelectGroup(group, index)
                    },
                    modifier = Modifier.onFocusChanged { focus ->
                        if (focus.isFocused) onFocusGroup(index)
                    },
                )
            }
        }

        if (categories.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "二级分区",
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
                        selected = category.id == selectedCategoryId,
                        onClick = {
                            onFocusCategory(index)
                            onSelectCategory(category, index)
                        },
                        modifier = Modifier.onFocusChanged { focus ->
                            if (focus.isFocused) onFocusCategory(index)
                        },
                    )
                }
            }
        }
    }
}

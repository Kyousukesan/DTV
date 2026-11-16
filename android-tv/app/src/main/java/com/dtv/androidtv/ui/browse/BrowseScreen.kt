package com.dtv.androidtv.ui.browse

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import com.dtv.androidtv.ui.theme.CinemaEmber

/**
 * Cinema Ember home: elevated platform tabs → 最近观看 → 关注 → categories → grid.
 * Platform Tab ←→ moves focus only; OK confirms [BrowseViewModel.selectPlatform].
 * Default focus: history first card when present (home.md).
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
    val historyFocus = remember { FocusRequester() }

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

    // Cold start: prefer 最近观看 first card when history exists.
    LaunchedEffect(state.loading, state.recent.size) {
        if (!state.loading && state.recent.isNotEmpty()) {
            runCatching { historyFocus.requestFocus() }
        }
    }

    TvScreenBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(TvLayout.ScreenPadding),
        ) {
            // Elevated top bar: platform tabs + utility actions.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CinemaEmber.BgElevated)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PlatformTabBar(
                    selected = state.platform,
                    focusedIndex = state.focusPlatform,
                    onFocusedIndex = viewModel::setFocusPlatform,
                    labelOf = catalog::label,
                    onConfirm = { platform, index -> viewModel.selectPlatform(platform, index) },
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvActionButton(label = "搜索", onClick = onOpenSearch)
                    TvActionButton(label = "设置", onClick = onOpenSettings)
                }
            }

            Spacer(Modifier.height(TvLayout.HeaderBottom))

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
                    // History-first (design §3); empty shows secondary hint.
                    RoomRow(
                        title = "最近观看",
                        rooms = state.recent,
                        focusedIndex = state.focusRecent,
                        onFocusedIndex = viewModel::setFocusRecent,
                        onOpenRoom = { room ->
                            viewModel.openRoom(room)
                            onOpenRoom(room)
                        },
                        emptyHint = "暂无观看记录",
                        cardWidth = TvLayout.CardWidth,
                        firstCardFocusRequester = if (state.recent.isNotEmpty()) {
                            historyFocus
                        } else {
                            null
                        },
                    )
                    Spacer(Modifier.height(TvLayout.RowSpacing))

                    // Keep working follow shelf (V1 design omits it; product still has it).
                    if (state.follows.isNotEmpty()) {
                        RoomRow(
                            title = "关注",
                            rooms = state.follows,
                            focusedIndex = state.focusFollows,
                            onFocusedIndex = viewModel::setFocusFollows,
                            onOpenRoom = { room ->
                                viewModel.openRoom(room)
                                onOpenRoom(room)
                            },
                            cardWidth = TvLayout.CardWidthCompact,
                        )
                        Spacer(Modifier.height(TvLayout.RowSpacing))
                    }

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
                    Spacer(Modifier.height(12.dp))

                    when {
                        state.loading && state.rooms.isEmpty() -> {
                            Text(
                                "加载分区直播…",
                                style = MaterialTheme.typography.titleMedium,
                                color = CinemaEmber.TextSecondary,
                            )
                        }
                        else -> {
                            Text(
                                text = "直播 · ${state.selectedCategoryName.ifBlank { "全部" }}",
                                style = MaterialTheme.typography.titleLarge,
                                color = CinemaEmber.TextPrimary,
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

/**
 * Top platform tabs. Focus moves with ←→; content switches only on OK ([onConfirm]).
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun PlatformTabBar(
    selected: LivePlatform,
    focusedIndex: Int,
    onFocusedIndex: (Int) -> Unit,
    labelOf: (LivePlatform) -> String,
    onConfirm: (LivePlatform, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // focusedIndex kept for ViewModel symmetry / future focusRestorer restore.
    @Suppress("UNUSED_PARAMETER")
    val _focused = focusedIndex
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = TvLayout.RowContentPadding,
        modifier = modifier.focusRestorer(),
    ) {
        itemsIndexed(LivePlatform.entries, key = { _, p -> p.id }) { index, platform ->
            CategoryChip(
                label = labelOf(platform),
                selected = platform == selected,
                onClick = {
                    onFocusedIndex(index)
                    onConfirm(platform, index)
                },
                modifier = Modifier.onFocusChanged { focus ->
                    if (focus.isFocused) onFocusedIndex(index)
                },
            )
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
            color = CinemaEmber.TextPrimary,
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
                color = CinemaEmber.TextPrimary,
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

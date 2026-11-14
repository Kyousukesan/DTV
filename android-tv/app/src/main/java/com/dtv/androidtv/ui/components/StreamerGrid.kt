package com.dtv.androidtv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dtv.androidtv.data.model.Streamer
import com.dtv.androidtv.ui.TvLayout

/**
 * Desktop-style streamer grid (HomeScreen StreamerGrid) for TV DPAD.
 * Fixed column count matches LT-14 shelf density (4 across).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamerGrid(
    rooms: List<Streamer>,
    focusedIndex: Int,
    onFocusedIndex: (Int) -> Unit,
    onOpenRoom: (Streamer) -> Unit,
    columns: Int = 4,
    emptyHint: String? = null,
    loadingMore: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (rooms.isEmpty()) {
        Text(
            text = emptyHint ?: "这个分区暂时没有主播在播",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.58f),
            modifier = Modifier.fillMaxWidth().then(modifier),
        )
        return
    }

    val gridState = rememberLazyGridState()
    LaunchedEffect(focusedIndex, rooms.size) {
        if (focusedIndex in rooms.indices) {
            gridState.animateScrollToItem(focusedIndex)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceAtLeast(2)),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(TvLayout.CardSpacing),
        verticalArrangement = Arrangement.spacedBy(TvLayout.CardSpacing),
    ) {
        itemsIndexed(
            rooms,
            key = { _, room -> "${room.platform}:${room.id}" },
        ) { index, room ->
            RoomCard(
                room = room,
                onClick = { onOpenRoom(room) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focus ->
                        if (focus.isFocused) onFocusedIndex(index)
                    },
            )
        }
        if (loadingMore) {
            item(key = "loading-more") {
                Text(
                    text = "加载更多…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                )
            }
        }
    }
}

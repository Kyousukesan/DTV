package com.dtv.androidtv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.dtv.androidtv.data.model.Streamer
import com.dtv.androidtv.ui.TvLayout

/**
 * Horizontal TV row with per-row focus memory.
 *
 * [focusedIndex] is remembered by the parent; [focusRestorer] restores the last
 * focused child when DPAD leaves and re-enters this row. We only scroll the
 * remembered index into view — we do **not** steal focus from sibling rows.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun RoomRow(
    title: String,
    rooms: List<Streamer>,
    focusedIndex: Int,
    onFocusedIndex: (Int) -> Unit,
    onOpenRoom: (Streamer) -> Unit,
    emptyHint: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = TvLayout.SectionTitleBottom),
        )
        if (rooms.isEmpty()) {
            Text(
                text = emptyHint ?: "暂无内容",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.58f),
                modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp),
            )
            return
        }

        val listState = rememberLazyListState()
        LaunchedEffect(focusedIndex, rooms.size) {
            if (rooms.isEmpty()) return@LaunchedEffect
            val target = focusedIndex.coerceIn(0, rooms.lastIndex)
            listState.animateScrollToItem(target)
        }

        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(TvLayout.CardSpacing),
            contentPadding = TvLayout.RowContentPadding,
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer(),
        ) {
            itemsIndexed(rooms, key = { _, room -> "${room.platform}:${room.id}" }) { index, room ->
                RoomCard(
                    room = room,
                    onClick = { onOpenRoom(room) },
                    modifier = Modifier
                        .width(280.dp)
                        .onFocusChanged { state ->
                            if (state.isFocused) onFocusedIndex(index)
                        },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RoomCard(
    room: Streamer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
        ),
        modifier = modifier,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color(0xFF1E2A44)),
            ) {
                if (room.cover.isNotBlank()) {
                    AsyncImage(
                        model = room.cover,
                        contentDescription = room.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (!room.isLive) {
                    Text(
                        text = "未开播",
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = room.nickname.ifBlank { "房间 ${room.id}" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        val plat = when (room.platform.lowercase()) {
                            "huya" -> "虎牙"
                            "douyin" -> "抖音"
                            "bilibili" -> "B站"
                            "douyu" -> "斗鱼"
                            else -> room.platform
                        }
                        if (plat.isNotBlank()) append(plat).append(" · ")
                        append(room.title.ifBlank { room.categoryName })
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
            },
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
            pressedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        ),
        modifier = modifier,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

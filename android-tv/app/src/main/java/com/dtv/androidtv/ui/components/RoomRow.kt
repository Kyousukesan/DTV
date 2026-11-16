package com.dtv.androidtv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.dtv.androidtv.data.model.Streamer
import com.dtv.androidtv.ui.TvLayout
import com.dtv.androidtv.ui.theme.CinemaEmber

/**
 * Horizontal TV row with per-row focus memory.
 *
 * [focusedIndex] is remembered by the parent; [focusRestorer] restores the last
 * focused child when DPAD leaves and re-enters this row. We only scroll the
 * remembered index into view — we do **not** steal focus from sibling rows.
 *
 * [title] may be blank to continue a multi-row live grid without repeating the
 * section heading. [indexOffset] maps local card index → global list index when
 * rooms are chunked across several rows.
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
    /** Added to local LazyRow index when reporting focus (multi-row live grid). */
    indexOffset: Int = 0,
    cardWidth: Dp = TvLayout.CardWidth,
    modifier: Modifier = Modifier,
    /** When true, empty row still reserves title (history empty-state). */
    showEmpty: Boolean = true,
    /** Optional focus target for the first card (cold-start history default). */
    firstCardFocusRequester: FocusRequester? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = CinemaEmber.TextPrimary,
                modifier = Modifier.padding(bottom = TvLayout.SectionTitleBottom),
            )
        }
        if (rooms.isEmpty()) {
            if (showEmpty) {
                Text(
                    text = emptyHint ?: "暂无内容",
                    style = MaterialTheme.typography.bodyLarge,
                    color = CinemaEmber.TextSecondary,
                    modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp),
                )
            }
            return
        }

        val listState = rememberLazyListState()
        // Only auto-scroll when the remembered global index lands in this row.
        val localFocused = focusedIndex - indexOffset
        LaunchedEffect(localFocused, rooms.size) {
            if (rooms.isEmpty()) return@LaunchedEffect
            if (localFocused !in rooms.indices) return@LaunchedEffect
            listState.animateScrollToItem(localFocused)
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
                val cardMod = Modifier
                    .width(cardWidth)
                    .onFocusChanged { state ->
                        if (state.isFocused) onFocusedIndex(indexOffset + index)
                    }
                    .let { base ->
                        if (index == 0 && firstCardFocusRequester != null) {
                            base.focusRequester(firstCardFocusRequester)
                        } else {
                            base
                        }
                    }
                RoomCard(
                    room = room,
                    onClick = { onOpenRoom(room) },
                    modifier = cardMod,
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
    val shape = RoundedCornerShape(CinemaEmber.RadiusCard)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = CinemaEmber.FocusScale,
            pressedScale = CinemaEmber.PressScale,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, CinemaEmber.BorderSubtle),
                shape = shape,
            ),
            focusedBorder = Border(
                border = BorderStroke(CinemaEmber.FocusStroke, CinemaEmber.FocusRing),
                shape = shape,
            ),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CinemaEmber.BgSurface,
            focusedContainerColor = CinemaEmber.BgSurfaceHover,
        ),
        modifier = modifier,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(CinemaEmber.BgSkeleton),
            ) {
                if (room.cover.isNotBlank()) {
                    AsyncImage(
                        model = room.cover,
                        contentDescription = room.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Bottom gradient for title readability on bright covers.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .aspectRatio(16f / 4.5f)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                            ),
                        ),
                )
                LiveBadge(
                    isLive = room.isLive,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )
            }
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = room.title.ifBlank { room.nickname.ifBlank { "房间 ${room.id}" } },
                    style = MaterialTheme.typography.titleMedium,
                    color = CinemaEmber.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(room.nickname.ifBlank { "未知主播" })
                        if (room.viewers > 0) {
                            append(" · ").append(formatViewers(room.viewers))
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinemaEmber.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun LiveBadge(isLive: Boolean, modifier: Modifier = Modifier) {
    val bg = if (isLive) CinemaEmber.Live else CinemaEmber.Offline
    val fg = if (isLive) CinemaEmber.LiveText else CinemaEmber.TextPrimary
    Text(
        text = if (isLive) "直播中" else "未开播",
        modifier = modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = fg,
    )
}

private fun formatViewers(n: Long): String =
    when {
        n >= 10_000L -> "${"%.1f".format(n / 10_000.0)}万"
        else -> n.toString()
    }

/**
 * Platform / category chip: selected = accent underline feel via muted fill;
 * focused = focus ring. ←→ focus alone does not select — OK triggers [onClick].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(CinemaEmber.RadiusControl)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = CinemaEmber.FocusScale,
            pressedScale = CinemaEmber.PressScale,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, CinemaEmber.BorderSubtle),
                shape = shape,
            ),
            focusedBorder = Border(
                border = BorderStroke(CinemaEmber.FocusStroke, CinemaEmber.FocusRing),
                shape = shape,
            ),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) CinemaEmber.AccentMuted else CinemaEmber.BgElevated,
            focusedContainerColor = CinemaEmber.BgSurfaceHover,
            contentColor = if (selected) CinemaEmber.TextPrimary else CinemaEmber.TextSecondary,
            focusedContentColor = CinemaEmber.TextPrimary,
        ),
        modifier = modifier,
    ) {
        Box {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                color = if (selected) CinemaEmber.TextPrimary else CinemaEmber.TextSecondary,
            )
            // Selected-but-unfocused accent bar (MASTER Platform Tab).
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .fillMaxWidth(0.55f)
                        .height(3.dp)
                        .background(CinemaEmber.Accent, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

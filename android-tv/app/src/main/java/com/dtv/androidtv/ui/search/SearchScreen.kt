package com.dtv.androidtv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.dtv.androidtv.data.PlatformCatalog
import com.dtv.androidtv.data.model.LivePlatform
import com.dtv.androidtv.data.model.SearchHit
import com.dtv.androidtv.data.model.Streamer
import com.dtv.androidtv.ui.TvLayout

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    catalog: PlatformCatalog,
    onOpenRoom: (Streamer) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = viewModel(
        factory = SearchViewModel.Factory(catalog),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val queryFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { queryFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B1020), Color(0xFF16213E), Color(0xFF0B1020)),
                ),
            )
            .padding(TvLayout.ScreenPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "搜索",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Surface(
                onClick = onBack,
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            ) {
                Text(
                    text = "返回",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        PlatformChips(
            selected = state.platform,
            labelOf = catalog::label,
            onClick = viewModel::setPlatform,
        )
        Spacer(Modifier.height(16.dp))

        Surface(
            onClick = { runCatching { queryFocus.requestFocus() } },
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BasicTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(queryFocus)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                decorationBox = { inner ->
                    Box {
                        if (state.query.isEmpty()) {
                            Text(
                                text = state.hint,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            )
                        }
                        inner()
                    }
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                onClick = { viewModel.search() },
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            ) {
                Text(
                    text = "搜索",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        when {
            state.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("搜索中…", style = MaterialTheme.typography.titleLarge)
                }
            }
            state.error != null && state.hits.isEmpty() -> {
                Text(
                    text = state.error ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                )
            }
            state.hits.isEmpty() -> {
                Text(
                    text = "输入关键词后按「搜索」或遥控器确认键",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.hits, key = { it.id }) { hit ->
                        SearchHitRow(
                            hit = hit,
                            onClick = { onOpenRoom(viewModel.toStreamer(hit)) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlatformChips(
    selected: LivePlatform,
    labelOf: (LivePlatform) -> String,
    onClick: (LivePlatform) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(LivePlatform.entries) { _, platform ->
            val active = platform == selected
            Surface(
                onClick = { onClick(platform) },
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (active) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                ),
            ) {
                Text(
                    text = labelOf(platform),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchHitRow(
    hit: SearchHit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = hit.avatar.ifBlank { null },
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(hit.nickname, style = MaterialTheme.typography.titleLarge)
                if (hit.title.isNotBlank()) {
                    Text(
                        hit.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    )
                }
            }
            Text(
                text = if (hit.isLive) "直播中" else "未开播",
                style = MaterialTheme.typography.labelLarge,
                color = if (hit.isLive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                },
            )
        }
    }
}

package com.dtv.androidtv.ui.player

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.TextureView
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.dtv.androidtv.DtvTvApp
import com.dtv.androidtv.R
import com.dtv.androidtv.data.DtvApi
import com.dtv.androidtv.player.StreamPlayback
import com.dtv.androidtv.ui.TvLayout
import kotlinx.coroutines.delay

/** Keeps PlayerView + canvas danmaku handles for [AndroidView] update. */
private class PlayerHostViews(
    val playerView: PlayerView,
    val danmaku: DanmakuCanvasView,
)

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    api: DtvApi,
    platformId: String,
    roomId: String,
    fallbackNickname: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModel.Factory(
            api = api,
            library = DtvTvApp.instance.libraryStore,
            catalog = DtvTvApp.instance.platforms,
            platformId = platformId,
            roomId = roomId,
            fallbackNickname = fallbackNickname,
        ),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    var isPlaying by remember { mutableStateOf(true) }

    // Desktop Esc: overlays → exit fullscreen → navigate.
    // TV Back maps the same layering: reveal chrome first, then leave the room.
    BackHandler {
        when {
            state.error != null || state.notLive -> onBack()
            !state.chromeVisible -> viewModel.setChromeVisible(true)
            else -> onBack()
        }
    }

    val playback = remember(platformId) {
        StreamPlayback(
            context = context,
            scope = scope,
            platformId = platformId,
            resolveUrl = { quality, line -> viewModel.resolveFreshUrl(quality, line) },
        )
    }

    DisposableEffect(Unit) {
        playback.onError = { msg -> viewModel.reportPlaybackError(msg) }
        playback.onPlaying = {
            viewModel.clearError()
            viewModel.setChromeVisible(false)
        }
        playback.onIsPlayingChanged = { playing -> isPlaying = playing }
        playback.onRelayEvent = { event ->
            android.util.Log.i("PlayerScreen", "flv-relay: $event")
        }
        onDispose { playback.release() }
    }

    LaunchedEffect(state.selected, state.selectedLineId, state.useTestHls) {
        when {
            state.useTestHls -> playback.playTestHls()
            state.selected != null -> {
                val v = state.selected!!
                val info = state.info
                playback.play(
                    roomId = info?.roomId ?: roomId,
                    url = v.url,
                    format = v.format,
                    quality = v.id,
                    line = state.selectedLineId.ifBlank {
                        info?.lines?.firstOrNull()?.id.orEmpty()
                    },
                )
            }
        }
    }

    // Auto-hide chrome while playing (DPAD / OK brings it back).
    LaunchedEffect(state.chromeVisible, state.error, state.notLive, state.loading, state.switching) {
        if (
            state.chromeVisible &&
            state.error == null &&
            !state.notLive &&
            !state.loading &&
            !state.switching
        ) {
            delay(4_000)
            viewModel.setChromeVisible(false)
        }
    }

    // When chrome is hidden there are no focusable chrome buttons — keep focus on
    // the root Box so DPAD_CENTER / ENTER reach onPreviewKeyEvent.
    LaunchedEffect(state.chromeVisible, state.error, state.notLive) {
        if (state.error != null || state.notLive) return@LaunchedEffect
        if (state.chromeVisible) {
            runCatching { playPauseFocus.requestFocus() }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    -> {
                        if (!state.chromeVisible && state.error == null && !state.notLive) {
                            viewModel.setChromeVisible(true)
                            true
                        } else if (!state.chromeVisible) {
                            false
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_MENU,
                    -> {
                        if (!state.chromeVisible && state.error == null && !state.notLive) {
                            viewModel.setChromeVisible(true)
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            },
    ) {
        if (!state.notLive && state.error == null) {
            val showDanmaku = roomId != "test" && state.danmakuSupported
            AndroidView(
                factory = { ctx ->
                    // TextureView surface (XML) + Canvas danmaku inside Media3's
                    // official exo_overlay — above the video surface, below chrome.
                    (LayoutInflater.from(ctx)
                        .inflate(R.layout.player_view_texture, null, false) as PlayerView)
                        .apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            isFocusable = false
                            isFocusableInTouchMode = false
                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                            useController = false
                            player = playback.player

                            val surface = videoSurfaceView
                            android.util.Log.i(
                                "PlayerScreen",
                                "surface=${surface?.javaClass?.simpleName} " +
                                    "texture=${surface is TextureView}",
                            )

                            val danmaku = DanmakuCanvasView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                elevation = 4f
                            }
                            val overlay = overlayFrameLayout
                                ?: error("PlayerView missing exo_overlay")
                            overlay.addView(danmaku)
                            tag = PlayerHostViews(this, danmaku)
                        }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(0f),
                update = { playerView ->
                    val host = playerView.tag as PlayerHostViews
                    host.playerView.player = playback.player
                    if (showDanmaku) {
                        host.danmaku.setOverlayEnabled(state.danmakuEnabled)
                        host.danmaku.submit(state.danmakuMessages)
                    } else {
                        host.danmaku.setOverlayEnabled(false)
                        host.danmaku.submit(emptyList())
                    }
                },
            )
        }

        if (state.loading || state.switching) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.switching) "正在切换线路…" else "正在解析直播流…",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        when {
            state.notLive -> {
                Box(Modifier.zIndex(2f).fillMaxSize()) {
                    StatusPanel(
                        title = state.info?.nickname ?: fallbackNickname.ifBlank { "直播间" },
                        message = "主播未开播",
                        primaryLabel = "重试",
                        onPrimary = { viewModel.load() },
                        secondaryLabel = "返回",
                        onSecondary = onBack,
                    )
                }
            }
            state.error != null -> {
                Box(Modifier.zIndex(2f).fillMaxSize()) {
                    StatusPanel(
                        title = "播放出错",
                        message = state.error ?: "",
                        primaryLabel = "重试",
                        onPrimary = { viewModel.load() },
                        secondaryLabel = "返回",
                        onSecondary = onBack,
                    )
                }
            }
            state.chromeVisible -> {
                val qualityText = state.selected?.let { "${it.label} · ${it.format.uppercase()}" }
                val lineText = state.lineLabel
                val danmakuLabel = when {
                    !state.danmakuEnabled -> "弹幕：关"
                    state.danmakuChatCount > 0 -> "弹幕：开 · ${state.danmakuChatCount}"
                    else -> "弹幕：开"
                }
                Box(Modifier.zIndex(2f).fillMaxSize()) {
                    PlayerChrome(
                        nickname = state.info?.nickname
                            ?: if (state.useTestHls) "测试流" else fallbackNickname,
                        title = state.info?.title
                            ?: if (state.useTestHls) "Media3 测试 HLS" else "",
                        qualityLabel = listOfNotNull(
                            qualityText?.let { "清晰度 $it" },
                            lineText?.let { "线路 $it" },
                        ).joinToString(" · ").ifBlank { null },
                        followed = state.followed,
                        danmakuButtonLabel = danmakuLabel,
                        playPauseFocus = playPauseFocus,
                        isPlaying = isPlaying,
                        canCycleQuality = state.canCycleQuality,
                        canCycleLine = state.canCycleLine,
                        qualityButtonLabel = state.selected?.label?.let { "切换清晰度：$it" }
                            ?: "切换清晰度",
                        lineButtonLabel = state.lineLabel?.let { "切换线路：$it" } ?: "切换线路",
                        onPlayPause = {
                            val p = playback.player
                            if (p.isPlaying) p.pause() else p.play()
                            isPlaying = p.playWhenReady
                            viewModel.setChromeVisible(true)
                        },
                        onToggleFollow = { viewModel.toggleFollow() },
                        onToggleDanmaku = { viewModel.toggleDanmaku() },
                        onCycleQuality = { viewModel.cycleQuality() },
                        onCycleLine = { viewModel.cycleLine() },
                        onBack = onBack,
                        showFollow = roomId != "test",
                        showDanmakuToggle = roomId != "test" && state.danmakuSupported,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayerChrome(
    nickname: String,
    title: String,
    qualityLabel: String?,
    followed: Boolean,
    danmakuButtonLabel: String,
    playPauseFocus: FocusRequester,
    isPlaying: Boolean,
    canCycleQuality: Boolean,
    canCycleLine: Boolean,
    qualityButtonLabel: String,
    lineButtonLabel: String,
    onPlayPause: () -> Unit,
    onToggleFollow: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onCycleQuality: () -> Unit,
    onCycleLine: () -> Unit,
    onBack: () -> Unit,
    showFollow: Boolean,
    showDanmakuToggle: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent),
                    ),
                )
                .padding(
                    start = TvLayout.SafeHorizontal,
                    end = TvLayout.SafeHorizontal,
                    top = TvLayout.SafeVertical,
                    bottom = 48.dp,
                ),
        ) {
            Column {
                Text(nickname, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                if (title.isNotBlank()) {
                    Text(
                        title,
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                qualityLabel?.let {
                    Text(
                        it,
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                    ),
                )
                .padding(
                    start = TvLayout.SafeHorizontal,
                    end = TvLayout.SafeHorizontal,
                    top = 40.dp,
                    bottom = TvLayout.SafeVertical,
                ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChromeButton(
                label = if (isPlaying) "暂停" else "播放",
                onClick = onPlayPause,
                modifier = Modifier.focusRequester(playPauseFocus),
            )
            if (showFollow) {
                ChromeButton(
                    label = if (followed) "取消关注" else "关注本房间",
                    onClick = onToggleFollow,
                )
            }
            if (showDanmakuToggle) {
                ChromeButton(
                    label = danmakuButtonLabel,
                    onClick = onToggleDanmaku,
                )
            }
            if (canCycleQuality) {
                ChromeButton(
                    label = qualityButtonLabel,
                    onClick = onCycleQuality,
                )
            }
            if (canCycleLine) {
                ChromeButton(
                    label = lineButtonLabel,
                    onClick = onCycleLine,
                )
            }
            Spacer(Modifier.weight(1f))
            ChromeButton(label = "退出播放", onClick = onBack)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChromeButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.14f),
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            pressedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
        ),
        modifier = modifier,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatusPanel(
    title: String,
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(TvLayout.ScreenPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ChromeButton(label = primaryLabel, onClick = onPrimary)
                ChromeButton(label = secondaryLabel, onClick = onSecondary)
            }
        }
    }
}

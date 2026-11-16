package com.dtv.androidtv.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.dtv.androidtv.DtvTvApp
import com.dtv.androidtv.R
import com.dtv.androidtv.data.DtvApi
import com.dtv.androidtv.data.library.DanmakuStyleCycles
import com.dtv.androidtv.player.StreamPlayback
import com.dtv.androidtv.ui.TvLayout
import com.dtv.androidtv.ui.theme.CinemaEmber
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
    val drawerFocus = remember { FocusRequester() }
    var isPlaying by remember { mutableStateOf(true) }
    // Layer 3: right drawer for danmaku settings (interaction §4.4 / player.md).
    var danmakuDrawerOpen by remember { mutableStateOf(false) }

    val keepAwakeTitle = state.info?.nickname
        ?: if (state.useTestHls) "测试流" else fallbackNickname.ifBlank { "直播" }
    PlayerKeepAwake(isPlaying = isPlaying, title = keepAwakeTitle)

    // Back: settings → controls → immersive → home (never skip layers).
    BackHandler {
        when {
            state.error != null || state.notLive -> onBack()
            danmakuDrawerOpen -> danmakuDrawerOpen = false
            state.chromeVisible -> viewModel.setChromeVisible(false)
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
            danmakuDrawerOpen = false
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

    // Auto-hide chrome while playing; pause timer while drawer is open.
    LaunchedEffect(
        state.chromeVisible,
        danmakuDrawerOpen,
        state.error,
        state.notLive,
        state.loading,
        state.switching,
    ) {
        if (
            state.chromeVisible &&
            !danmakuDrawerOpen &&
            state.error == null &&
            !state.notLive &&
            !state.loading &&
            !state.switching
        ) {
            delay(5_000)
            viewModel.setChromeVisible(false)
        }
    }

    LaunchedEffect(state.chromeVisible, danmakuDrawerOpen, state.error, state.notLive) {
        if (state.error != null || state.notLive) return@LaunchedEffect
        when {
            danmakuDrawerOpen -> runCatching { drawerFocus.requestFocus() }
            state.chromeVisible -> runCatching { playPauseFocus.requestFocus() }
            else -> runCatching { rootFocus.requestFocus() }
        }
    }

    fun openChrome() {
        viewModel.setChromeVisible(true)
    }

    fun openDanmakuDrawer() {
        viewModel.setChromeVisible(true)
        danmakuDrawerOpen = true
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
                            openChrome()
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_MENU,
                    -> {
                        if (state.error == null && !state.notLive &&
                            roomId != "test" && state.danmakuSupported
                        ) {
                            openDanmakuDrawer()
                            true
                        } else if (!state.chromeVisible && state.error == null && !state.notLive) {
                            openChrome()
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    -> {
                        if (!state.chromeVisible && state.error == null && !state.notLive) {
                            openChrome()
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
                        host.danmaku.applyStyle(state.danmakuStyle)
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
                    color = CinemaEmber.TextPrimary,
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
                        secondaryLabel = "返回首页",
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
                        secondaryLabel = "返回首页",
                        onSecondary = onBack,
                    )
                }
            }
            state.chromeVisible || danmakuDrawerOpen -> {
                val style = state.danmakuStyle
                Box(Modifier.zIndex(2f).fillMaxSize()) {
                    if (state.chromeVisible && !danmakuDrawerOpen) {
                        // Light scrim only under chrome — keep picture readable.
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.28f)),
                        )
                    }
                    if (danmakuDrawerOpen) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(CinemaEmber.Overlay),
                        )
                    }

                    if (state.chromeVisible) {
                        PlayerChrome(
                            nickname = state.info?.nickname
                                ?: if (state.useTestHls) "测试流" else fallbackNickname,
                            title = state.info?.title
                                ?: if (state.useTestHls) "Media3 测试 HLS" else "",
                            metaLabel = listOfNotNull(
                                state.selected?.let { "${it.label} · ${it.format.uppercase()}" },
                                state.lineLabel,
                            ).joinToString(" · ").ifBlank { null },
                            playPauseFocus = playPauseFocus,
                            isPlaying = isPlaying,
                            canCycleQuality = state.canCycleQuality,
                            canCycleLine = state.canCycleLine,
                            qualityButtonLabel = state.selected?.label?.let { "清晰度：$it" }
                                ?: "清晰度",
                            lineButtonLabel = state.lineLabel?.let { "线路：$it" } ?: "线路",
                            followed = state.followed,
                            showFollow = roomId != "test",
                            showDanmaku = roomId != "test" && state.danmakuSupported,
                            danmakuOn = state.danmakuEnabled,
                            onPlayPause = {
                                val p = playback.player
                                if (p.isPlaying) p.pause() else p.play()
                                isPlaying = p.playWhenReady
                                viewModel.setChromeVisible(true)
                            },
                            onCycleQuality = { viewModel.cycleQuality() },
                            onCycleLine = { viewModel.cycleLine() },
                            onToggleFollow = { viewModel.toggleFollow() },
                            onOpenDanmaku = { openDanmakuDrawer() },
                        )
                    }

                    AnimatedVisibility(
                        visible = danmakuDrawerOpen,
                        enter = slideInHorizontally { it } + fadeIn(),
                        exit = slideOutHorizontally { it } + fadeOut(),
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        DanmakuSettingsDrawer(
                            enabled = state.danmakuEnabled,
                            fontLabel = "字号 ${style.fontSizePx}",
                            opacityLabel = "透明 ${DanmakuStyleCycles.formatOpacity(style.opacity)}",
                            speedLabel = "速度 ${DanmakuStyleCycles.formatSpeedLabel(style.durationMs)}",
                            chatCount = state.danmakuChatCount,
                            firstFocus = drawerFocus,
                            onToggle = { viewModel.toggleDanmaku() },
                            onCycleFont = { viewModel.cycleDanmakuFont() },
                            onCycleOpacity = { viewModel.cycleDanmakuOpacity() },
                            onCycleSpeed = { viewModel.cycleDanmakuSpeed() },
                            onClose = { danmakuDrawerOpen = false },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Holds the Activity window awake and publishes a playing MediaSession while
 * [PlayerScreen] is composed; both are cleared in [DisposableEffect] onDispose
 * so leaving the player restores normal TV screensaver / standby behavior.
 */
@Composable
private fun PlayerKeepAwake(isPlaying: Boolean, title: String) {
    val view = LocalView.current
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val mediaSession = remember(context) {
        MediaSession(context.applicationContext, "dtv-live").apply {
            setCallback(object : MediaSession.Callback() {})
        }
    }

    DisposableEffect(Unit) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view.keepScreenOn = true
        mediaSession.isActive = true
        onDispose {
            view.keepScreenOn = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            mediaSession.isActive = false
            mediaSession.release()
        }
    }

    LaunchedEffect(isPlaying, title) {
        val display = title.ifBlank { "直播" }
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, display)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, display)
                .build(),
        )
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setState(
                    if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (isPlaying) 1f else 0f,
                )
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE,
                )
                .build(),
        )
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

/** Top info + bottom control bar (design §5.2 / §6.6.4). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayerChrome(
    nickname: String,
    title: String,
    metaLabel: String?,
    playPauseFocus: FocusRequester,
    isPlaying: Boolean,
    canCycleQuality: Boolean,
    canCycleLine: Boolean,
    qualityButtonLabel: String,
    lineButtonLabel: String,
    followed: Boolean,
    showFollow: Boolean,
    showDanmaku: Boolean,
    danmakuOn: Boolean,
    onPlayPause: () -> Unit,
    onCycleQuality: () -> Unit,
    onCycleLine: () -> Unit,
    onToggleFollow: () -> Unit,
    onOpenDanmaku: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(CinemaEmber.BgElevated.copy(alpha = 0.92f))
                .padding(
                    start = TvLayout.SafeHorizontal,
                    end = TvLayout.SafeHorizontal,
                    top = TvLayout.SafeVertical,
                    bottom = 16.dp,
                ),
        ) {
            Text(
                nickname,
                color = CinemaEmber.TextPrimary,
                style = MaterialTheme.typography.titleLarge,
            )
            if (title.isNotBlank()) {
                Text(
                    title,
                    color = CinemaEmber.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            metaLabel?.let {
                Text(
                    it,
                    color = CinemaEmber.TextSecondary.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(104.dp)
                .background(CinemaEmber.BgElevated.copy(alpha = 0.92f))
                .padding(
                    start = TvLayout.SafeHorizontal,
                    end = TvLayout.SafeHorizontal,
                    top = 16.dp,
                    bottom = TvLayout.SafeVertical,
                ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChromeButton(
                label = if (isPlaying) "暂停" else "播放",
                onClick = onPlayPause,
                modifier = Modifier.focusRequester(playPauseFocus),
            )
            if (canCycleQuality) {
                ChromeButton(label = qualityButtonLabel, onClick = onCycleQuality)
            }
            if (canCycleLine) {
                ChromeButton(label = lineButtonLabel, onClick = onCycleLine)
            }
            if (showDanmaku) {
                ChromeButton(
                    label = if (danmakuOn) "弹幕设置" else "弹幕：关",
                    onClick = onOpenDanmaku,
                )
            }
            if (showFollow) {
                ChromeButton(
                    label = if (followed) "取消关注" else "关注",
                    onClick = onToggleFollow,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "返回 · 分层退出",
                color = CinemaEmber.TextSecondary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** Right-side danmaku settings drawer (~38% width). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DanmakuSettingsDrawer(
    enabled: Boolean,
    fontLabel: String,
    opacityLabel: String,
    speedLabel: String,
    chatCount: Int,
    firstFocus: FocusRequester,
    onToggle: () -> Unit,
    onCycleFont: () -> Unit,
    onCycleOpacity: () -> Unit,
    onCycleSpeed: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.38f)
            .widthIn(min = 360.dp, max = 480.dp)
            .background(CinemaEmber.BgSurface)
            .padding(horizontal = 28.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "弹幕设置",
            style = MaterialTheme.typography.titleLarge,
            color = CinemaEmber.TextPrimary,
        )
        Text(
            if (chatCount > 0) "本场已收 $chatCount 条" else "调节后立即生效",
            style = MaterialTheme.typography.bodyMedium,
            color = CinemaEmber.TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        DrawerRow(
            label = if (enabled) "弹幕：开" else "弹幕：关",
            onClick = onToggle,
            modifier = Modifier.focusRequester(firstFocus),
            emphasized = enabled,
        )
        DrawerRow(label = fontLabel, onClick = onCycleFont)
        DrawerRow(label = opacityLabel, onClick = onCycleOpacity)
        DrawerRow(label = speedLabel, onClick = onCycleSpeed)
        Spacer(Modifier.weight(1f))
        Text(
            "屏蔽词 / 弹幕量档位 · 后续版本",
            style = MaterialTheme.typography.bodyMedium,
            color = CinemaEmber.TextDisabled,
        )
        DrawerRow(label = "关闭设置", onClick = onClose)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DrawerRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
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
            focusedBorder = Border(
                border = BorderStroke(CinemaEmber.FocusStroke, CinemaEmber.FocusRing),
                shape = shape,
            ),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (emphasized) CinemaEmber.AccentMuted else CinemaEmber.BgElevated,
            focusedContainerColor = CinemaEmber.BgSurfaceHover,
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = CinemaEmber.TextPrimary,
            )
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
    val shape = RoundedCornerShape(CinemaEmber.RadiusControl)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = CinemaEmber.FocusScale,
            pressedScale = CinemaEmber.PressScale,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(CinemaEmber.FocusStroke, CinemaEmber.FocusRing),
                shape = shape,
            ),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CinemaEmber.BgSurface.copy(alpha = 0.9f),
            focusedContainerColor = CinemaEmber.AccentMuted,
            pressedContainerColor = CinemaEmber.Accent.copy(alpha = 0.45f),
        ),
        modifier = modifier.height(72.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelLarge,
            color = CinemaEmber.TextPrimary,
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
            Text(
                title,
                color = CinemaEmber.TextPrimary,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                color = CinemaEmber.TextSecondary,
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

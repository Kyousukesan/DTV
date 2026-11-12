package com.dtv.androidtv.ui.player

import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
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
import com.dtv.androidtv.data.DtvApi
import com.dtv.androidtv.player.StreamPlayback
import com.dtv.androidtv.ui.TvLayout
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    api: DtvApi,
    roomId: String,
    fallbackNickname: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModel.Factory(
            api = api,
            library = DtvTvApp.instance.libraryStore,
            roomId = roomId,
            fallbackNickname = fallbackNickname,
        ),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playPauseFocus = remember { FocusRequester() }
    var isPlaying by remember { mutableStateOf(true) }

    BackHandler(onBack = onBack)

    val playback = remember {
        StreamPlayback(
            context = context,
            scope = scope,
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

    LaunchedEffect(state.selected, state.useTestHls) {
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
                    line = info?.lines?.firstOrNull()?.id.orEmpty(),
                )
            }
        }
    }

    // Auto-hide chrome while playing (DPAD / OK brings it back).
    LaunchedEffect(state.chromeVisible, state.error, state.notLive, state.loading) {
        if (state.chromeVisible && state.error == null && !state.notLive && !state.loading) {
            delay(4_000)
            viewModel.setChromeVisible(false)
        }
    }

    LaunchedEffect(state.chromeVisible) {
        if (state.chromeVisible && state.error == null && !state.notLive) {
            runCatching { playPauseFocus.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    -> {
                        if (!state.chromeVisible && state.error == null && !state.notLive) {
                            // First OK: show chrome (focus lands on play/pause).
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
                    KeyEvent.KEYCODE_MENU,
                    -> {
                        viewModel.setChromeVisible(true)
                        false
                    }
                    else -> false
                }
            },
    ) {
        if (!state.notLive && state.error == null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        // TV chrome is Compose-driven; hide Media3 touch controller.
                        useController = false
                        player = playback.player
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { it.player = playback.player },
            )
        }

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "正在解析直播流…",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        when {
            state.notLive -> {
                StatusPanel(
                    title = state.info?.nickname ?: fallbackNickname.ifBlank { "直播间" },
                    message = "主播未开播",
                    primaryLabel = "重试",
                    onPrimary = { viewModel.load() },
                    secondaryLabel = "返回",
                    onSecondary = onBack,
                )
            }
            state.error != null -> {
                StatusPanel(
                    title = "播放出错",
                    message = state.error ?: "",
                    primaryLabel = "重试",
                    onPrimary = { viewModel.load() },
                    secondaryLabel = "返回",
                    onSecondary = onBack,
                )
            }
            state.chromeVisible -> {
                PlayerChrome(
                    nickname = state.info?.nickname
                        ?: if (state.useTestHls) "测试流" else fallbackNickname,
                    title = state.info?.title
                        ?: if (state.useTestHls) "Media3 测试 HLS" else "",
                    qualityLabel = state.selected?.let { "${it.label} · ${it.format.uppercase()}" },
                    followed = state.followed,
                    playPauseFocus = playPauseFocus,
                    isPlaying = isPlaying,
                    onPlayPause = {
                        val p = playback.player
                        if (p.isPlaying) p.pause() else p.play()
                        isPlaying = p.playWhenReady
                        viewModel.setChromeVisible(true)
                    },
                    onToggleFollow = { viewModel.toggleFollow() },
                    onBack = onBack,
                    showFollow = roomId != "test",
                )
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
    playPauseFocus: FocusRequester,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onToggleFollow: () -> Unit,
    onBack: () -> Unit,
    showFollow: Boolean,
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
                    label = if (followed) "已关注" else "关注",
                    onClick = onToggleFollow,
                )
            }
            Spacer(Modifier.weight(1f))
            ChromeButton(label = "返回", onClick = onBack)
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
        modifier = modifier,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
            style = MaterialTheme.typography.labelLarge,
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
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ChromeButton(label = primaryLabel, onClick = onPrimary)
                ChromeButton(label = secondaryLabel, onClick = onSecondary)
            }
        }
    }
}

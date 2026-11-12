package com.dtv.androidtv.ui.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
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
import com.dtv.androidtv.data.DtvApi
import com.dtv.androidtv.player.StreamPlayback

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    api: DtvApi,
    roomId: String,
    fallbackNickname: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory(api, roomId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var overlayVisible by remember { mutableStateOf(true) }

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
            overlayVisible = false
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = true
                    controllerShowTimeoutMs = 4000
                    player = playback.player
                    setOnClickListener { overlayVisible = !overlayVisible }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { it.player = playback.player },
        )

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在解析直播流…", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }

        if (state.error != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.error ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 48.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                    Surface(
                        onClick = { viewModel.load() },
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                    ) {
                        Text(
                            "重试",
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        onClick = onBack,
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                    ) {
                        Text(
                            "返回",
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        } else if (overlayVisible) {
            val title = state.info?.title
                ?: if (state.useTestHls) "Media3 测试 HLS" else fallbackNickname
            val nick = state.info?.nickname ?: fallbackNickname
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(40.dp)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(16.dp),
            ) {
                Text(nick, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(title, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge)
                state.selected?.let { v ->
                    Text(
                        "${v.label} · ${v.format.uppercase()}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

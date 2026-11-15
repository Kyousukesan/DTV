package com.dtv.androidtv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.dtv.androidtv.DtvTvApp
import com.dtv.androidtv.data.PlatformCatalog
import com.dtv.androidtv.data.model.AuthAccount
import com.dtv.androidtv.data.model.LivePlatform
import com.dtv.androidtv.data.model.QRLoginStatus
import com.dtv.androidtv.data.library.DanmakuStyleCycles
import com.dtv.androidtv.ui.TvActionButton
import com.dtv.androidtv.ui.TvLayout
import com.dtv.androidtv.ui.TvScreenBackground
import com.dtv.androidtv.ui.TvSectionHeader
import com.dtv.androidtv.ui.TvSettingsRow

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    catalog: PlatformCatalog,
    onBack: () -> Unit,
    onPlayTest: (() -> Unit)? = null,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            catalog = catalog,
            authStore = DtvTvApp.instance.authStore,
            library = DtvTvApp.instance.libraryStore,
        ),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val qr by viewModel.qr.collectAsStateWithLifecycle()

    BackHandler {
        if (qr != null) viewModel.cancelQr() else onBack()
    }

    TvScreenBackground {
        if (qr != null) {
            QrLoginPanel(
                state = qr!!,
                platformLabel = catalog.label(qr!!.platform),
                onRefresh = viewModel::refreshQr,
                onCancel = viewModel::cancelQr,
            )
        } else {
            SettingsHome(
                catalog = catalog,
                state = state,
                onBack = onBack,
                onLogin = viewModel::startQrLogin,
                onLogout = viewModel::logout,
                onToggleDanmaku = viewModel::toggleDanmakuDefault,
                onCycleFont = viewModel::cycleDanmakuFont,
                onCycleOpacity = viewModel::cycleDanmakuOpacity,
                onCycleSpeed = viewModel::cycleDanmakuSpeed,
                onClearFollows = viewModel::clearFollows,
                onClearRecent = viewModel::clearRecent,
                onPlayTest = onPlayTest,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsHome(
    catalog: PlatformCatalog,
    state: SettingsUiState,
    onBack: () -> Unit,
    onLogin: (LivePlatform) -> Unit,
    onLogout: (LivePlatform) -> Unit,
    onToggleDanmaku: () -> Unit,
    onCycleFont: () -> Unit,
    onCycleOpacity: () -> Unit,
    onCycleSpeed: () -> Unit,
    onClearFollows: () -> Unit,
    onClearRecent: () -> Unit,
    onPlayTest: (() -> Unit)?,
) {
    val backFocus = remember { FocusRequester() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(TvLayout.ScreenPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("设置", style = MaterialTheme.typography.displaySmall)
                Text(
                    "账号、弹幕默认与本地数据",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            TvActionButton(
                label = "返回",
                onClick = onBack,
                modifier = Modifier.focusRequester(backFocus),
            )
        }

        Spacer(Modifier.height(TvLayout.HeaderBottom))

        TvSectionHeader(
            title = "账号",
            subtitle = "扫码登录解锁斗鱼原画 / B 站更高清晰度（虎牙、抖音暂无扫码）",
        )
        catalog.loginPlatforms.forEach { platform ->
            val account = state.accounts.firstOrNull { it.platform == platform.id }
                ?: AuthAccount(platform = platform.id, loggedIn = false)
            TvSettingsRow(
                title = catalog.label(platform),
                subtitle = if (account.loggedIn) {
                    "已登录 · ${account.userName.ifBlank { "账号" }}"
                } else {
                    "未登录 · 遥控器确认后显示二维码"
                },
                actionLabel = if (account.loggedIn) "退出" else "扫码登录",
                onClick = {
                    if (account.loggedIn) onLogout(platform) else onLogin(platform)
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(TvLayout.SectionGap))
        TvSectionHeader(
            title = "播放偏好",
            subtitle = "与桌面弹幕设置同范围；播放页 chrome 也可循环调节",
        )
        TvSettingsRow(
            title = "弹幕默认",
            subtitle = if (state.danmakuDefaultOn) "开启（推荐）" else "关闭",
            actionLabel = if (state.danmakuDefaultOn) "切换为关" else "切换为开",
            onClick = onToggleDanmaku,
        )
        Spacer(Modifier.height(12.dp))
        val style = state.danmakuStyle
        TvSettingsRow(
            title = "弹幕字号",
            subtitle = "${style.fontSizePx}px（14–30，步进 2）",
            actionLabel = "增大 / 循环",
            onClick = onCycleFont,
        )
        Spacer(Modifier.height(12.dp))
        TvSettingsRow(
            title = "弹幕透明度",
            subtitle = "${DanmakuStyleCycles.formatOpacity(style.opacity)}（20%–100%）",
            actionLabel = "增大 / 循环",
            onClick = onCycleOpacity,
        )
        Spacer(Modifier.height(12.dp))
        TvSettingsRow(
            title = "弹幕速度",
            subtitle = DanmakuStyleCycles.formatSpeedLabel(style.durationMs),
            actionLabel = "变慢 / 循环",
            onClick = onCycleSpeed,
        )

        Spacer(Modifier.height(TvLayout.SectionGap + 8.dp))
        TvSectionHeader(
            title = "本地数据",
            subtitle = "仅本机 SharedPreferences，不会同步到桌面端",
        )
        TvSettingsRow(
            title = "清空关注",
            subtitle = "当前 ${state.followCount} 个关注",
            actionLabel = if (state.followCount > 0) "清空" else "无数据",
            onClick = { if (state.followCount > 0) onClearFollows() },
        )
        Spacer(Modifier.height(12.dp))
        TvSettingsRow(
            title = "清空最近观看",
            subtitle = "当前 ${state.recentCount} 条记录",
            actionLabel = if (state.recentCount > 0) "清空" else "无数据",
            onClick = { if (state.recentCount > 0) onClearRecent() },
        )

        Spacer(Modifier.height(TvLayout.SectionGap + 8.dp))
        TvSectionHeader(title = "关于")
        Text(
            state.versionLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
        )
        Text(
            "Compose for TV · Media3 · 斗鱼 / 虎牙 / 抖音 / B 站",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 6.dp),
        )
        if (onPlayTest != null) {
            Spacer(Modifier.height(16.dp))
            TvActionButton(label = "播放测试 HLS", onClick = onPlayTest)
        }

        state.message?.let {
            Spacer(Modifier.height(20.dp))
            Text(it, color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QrLoginPanel(
    state: QrLoginUiState,
    platformLabel: String,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(TvLayout.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "$platformLabel 扫码登录",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            state.statusMessage,
            style = MaterialTheme.typography.titleMedium,
            color = when (state.status) {
                QRLoginStatus.SUCCESS -> Color(0xFF7CFFB2)
                QRLoginStatus.EXPIRED, QRLoginStatus.ERROR -> Color(0xFFFF8A80)
                QRLoginStatus.SCANNED -> Color(0xFFFFD54F)
                else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
            },
        )
        Spacer(Modifier.height(24.dp))
        val url = state.code?.url
        if (!state.loading && !url.isNullOrBlank() && !state.success) {
            val bitmap = remember(url) { encodeQrImageBitmap(url, sizePx = 720) }
            Image(
                bitmap = bitmap,
                contentDescription = "登录二维码",
                modifier = Modifier
                    .size(360.dp)
                    .background(Color.White)
                    .padding(12.dp),
            )
        } else if (state.loading) {
            Text("申请二维码中…", style = MaterialTheme.typography.titleLarge)
        } else if (state.success) {
            Text(
                "可用更高清晰度取流 · 按返回关闭",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.status == QRLoginStatus.EXPIRED || state.error != null) {
                Surface(
                    onClick = onRefresh,
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                ) {
                    Text("刷新二维码", Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                }
            }
            Surface(
                onClick = onCancel,
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
            ) {
                Text(
                    if (state.success) "完成" else "取消",
                    Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}

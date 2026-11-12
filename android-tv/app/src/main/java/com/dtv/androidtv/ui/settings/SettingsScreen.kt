package com.dtv.androidtv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import com.dtv.androidtv.ui.TvLayout

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    catalog: PlatformCatalog,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(catalog, DtvTvApp.instance.authStore),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val qr by viewModel.qr.collectAsStateWithLifecycle()

    BackHandler {
        if (qr != null) viewModel.cancelQr() else onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B1020), Color(0xFF16213E), Color(0xFF0B1020)),
                ),
            )
            .padding(TvLayout.ScreenPadding),
    ) {
        if (qr != null) {
            QrLoginPanel(
                state = qr!!,
                platformLabel = catalog.label(qr!!.platform),
                onRefresh = viewModel::refreshQr,
                onCancel = viewModel::cancelQr,
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Text("账号设置", style = MaterialTheme.typography.displaySmall)
                Text(
                    "扫码登录解锁斗鱼原画 / B 站更高清晰度（虎牙、抖音暂无扫码）",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
                catalog.loginPlatforms.forEach { platform ->
                    val account = state.accounts.firstOrNull { it.platform == platform.id }
                        ?: AuthAccount(platform = platform.id, loggedIn = false)
                    AccountRow(
                        title = catalog.label(platform),
                        account = account,
                        onLogin = { viewModel.startQrLogin(platform) },
                        onLogout = { viewModel.logout(platform) },
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Spacer(Modifier.weight(1f))
                state.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                }
                Surface(
                    onClick = onBack,
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                ) {
                    Text(
                        "返回",
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AccountRow(
    title: String,
    account: AuthAccount,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    Surface(
        onClick = { if (account.loggedIn) onLogout() else onLogin() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (account.loggedIn) {
                        "已登录 · ${account.userName.ifBlank { "账号" }}"
                    } else {
                        "未登录"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
            Text(
                if (account.loggedIn) "退出登录" else "扫码登录",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
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
        modifier = Modifier.fillMaxSize(),
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

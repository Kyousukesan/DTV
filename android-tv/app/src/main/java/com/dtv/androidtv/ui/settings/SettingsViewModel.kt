package com.dtv.androidtv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dtv.androidtv.BuildConfig
import com.dtv.androidtv.data.PlatformCatalog
import com.dtv.androidtv.data.auth.AuthStore
import com.dtv.androidtv.data.library.LibraryStore
import com.dtv.androidtv.data.model.AuthAccount
import com.dtv.androidtv.data.model.LivePlatform
import com.dtv.androidtv.data.model.QRLoginCode
import com.dtv.androidtv.data.model.QRLoginResult
import com.dtv.androidtv.data.model.QRLoginStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val accounts: List<AuthAccount> = emptyList(),
    /** Default for new player sessions (LibraryStore). */
    val danmakuDefaultOn: Boolean = true,
    val followCount: Int = 0,
    val recentCount: Int = 0,
    val versionLabel: String = "",
    val message: String? = null,
)

data class QrLoginUiState(
    val platform: LivePlatform,
    val loading: Boolean = true,
    val code: QRLoginCode? = null,
    val status: String = QRLoginStatus.PENDING,
    val statusMessage: String = "正在申请二维码…",
    val error: String? = null,
    val success: Boolean = false,
    val userName: String = "",
)

class SettingsViewModel(
    private val catalog: PlatformCatalog,
    private val authStore: AuthStore,
    private val library: LibraryStore,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _qr = MutableStateFlow<QrLoginUiState?>(null)
    val qr: StateFlow<QrLoginUiState?> = _qr.asStateFlow()

    private var pollJob: Job? = null

    init {
        refreshAll()
    }

    fun refreshAll() {
        _state.update {
            it.copy(
                accounts = catalog.loginPlatforms.map { p -> authStore.loadAccount(p) },
                danmakuDefaultOn = library.isDanmakuEnabled(),
                followCount = library.followCount(),
                recentCount = library.recentCount(),
                versionLabel = buildVersionLabel(),
            )
        }
    }

    fun refreshAccounts() {
        _state.update {
            it.copy(accounts = catalog.loginPlatforms.map { p -> authStore.loadAccount(p) })
        }
    }

    fun toggleDanmakuDefault() {
        val next = !_state.value.danmakuDefaultOn
        library.setDanmakuEnabled(next)
        _state.update {
            it.copy(
                danmakuDefaultOn = next,
                message = if (next) "新开播房间默认开启弹幕" else "新开播房间默认关闭弹幕",
            )
        }
    }

    fun clearFollows() {
        library.clearFollows()
        _state.update {
            it.copy(followCount = 0, message = "已清空关注列表")
        }
    }

    fun clearRecent() {
        library.clearRecent()
        _state.update {
            it.copy(recentCount = 0, message = "已清空最近观看")
        }
    }

    fun logout(platform: LivePlatform) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                when (platform) {
                    LivePlatform.DOUYU -> {
                        runCatching { catalog.douyuClient.logout() }
                        catalog.douyuClient.setCookie("")
                    }
                    LivePlatform.BILIBILI -> {
                        runCatching { catalog.bilibiliClient.logout() }
                        catalog.bilibiliClient.setCookie("")
                    }
                    else -> Unit
                }
                authStore.clear(platform)
            }
            refreshAccounts()
            _state.update { it.copy(message = "${catalog.label(platform)} 已退出登录") }
        }
    }

    fun startQrLogin(platform: LivePlatform) {
        pollJob?.cancel()
        _qr.value = QrLoginUiState(platform = platform)
        viewModelScope.launch {
            try {
                val code = withContext(Dispatchers.IO) {
                    when (platform) {
                        LivePlatform.DOUYU -> catalog.douyuClient.qrLoginGenerate()
                        LivePlatform.BILIBILI -> catalog.bilibiliClient.qrLoginGenerate()
                        else -> error("该平台不支持扫码登录")
                    }
                }
                _qr.update {
                    it?.copy(
                        loading = false,
                        code = code,
                        status = QRLoginStatus.PENDING,
                        statusMessage = "请用手机${catalog.label(platform)} App 扫码",
                        error = null,
                    )
                }
                startPolling(platform, code.key)
            } catch (e: Exception) {
                _qr.update {
                    it?.copy(
                        loading = false,
                        error = e.message ?: "申请二维码失败",
                        status = QRLoginStatus.ERROR,
                        statusMessage = e.message ?: "申请二维码失败",
                    )
                }
            }
        }
    }

    fun refreshQr() {
        val platform = _qr.value?.platform ?: return
        startQrLogin(platform)
    }

    fun cancelQr() {
        pollJob?.cancel()
        pollJob = null
        _qr.value = null
    }

    private fun startPolling(platform: LivePlatform, key: String) {
        pollJob?.cancel()
        val intervalMs = if (platform == LivePlatform.BILIBILI) 1_500L else 2_000L
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(intervalMs)
                val result = try {
                    withContext(Dispatchers.IO) { pollOnce(platform, key) }
                } catch (e: Exception) {
                    _qr.update {
                        it?.copy(
                            status = QRLoginStatus.ERROR,
                            statusMessage = e.message ?: "轮询失败",
                            error = e.message,
                        )
                    }
                    continue
                }
                handlePollResult(platform, result)
                if (result.status == QRLoginStatus.SUCCESS ||
                    result.status == QRLoginStatus.EXPIRED ||
                    result.status == QRLoginStatus.ERROR
                ) {
                    break
                }
            }
        }
    }

    private fun pollOnce(platform: LivePlatform, key: String): QRLoginResult =
        when (platform) {
            LivePlatform.DOUYU -> catalog.douyuClient.qrLoginPoll(key)
            LivePlatform.BILIBILI -> catalog.bilibiliClient.qrLoginPoll(key)
            else -> QRLoginResult(QRLoginStatus.ERROR, "不支持")
        }

    private fun handlePollResult(platform: LivePlatform, result: QRLoginResult) {
        when (result.status) {
            QRLoginStatus.SUCCESS -> {
                authStore.saveSession(
                    platform,
                    result.cookie,
                    userName = result.userName,
                    avatar = result.avatar,
                )
                refreshAccounts()
                _qr.update {
                    it?.copy(
                        success = true,
                        status = QRLoginStatus.SUCCESS,
                        statusMessage = "登录成功${if (result.userName.isNotBlank()) " · ${result.userName}" else ""}",
                        userName = result.userName,
                        error = null,
                    )
                }
            }
            QRLoginStatus.SCANNED -> _qr.update {
                it?.copy(status = result.status, statusMessage = result.message.ifBlank { "已扫码，请在手机上确认" })
            }
            QRLoginStatus.PENDING -> _qr.update {
                it?.copy(status = result.status, statusMessage = result.message.ifBlank { "等待手机扫码" })
            }
            QRLoginStatus.EXPIRED -> _qr.update {
                it?.copy(status = result.status, statusMessage = result.message.ifBlank { "二维码已失效" })
            }
            else -> _qr.update {
                it?.copy(status = result.status, statusMessage = result.message, error = result.message)
            }
        }
    }

    class Factory(
        private val catalog: PlatformCatalog,
        private val authStore: AuthStore,
        private val library: LibraryStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(catalog, authStore, library) as T
    }

    companion object {
        fun buildVersionLabel(): String {
            val name = BuildConfig.VERSION_NAME
            val code = BuildConfig.VERSION_CODE
            val buildType = BuildConfig.BUILD_TYPE
            return "DTV for Android TV · v$name ($code · $buildType)"
        }
    }
}

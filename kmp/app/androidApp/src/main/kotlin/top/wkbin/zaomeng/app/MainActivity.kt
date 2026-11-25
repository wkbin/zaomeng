package top.wkbin.zaomeng.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.lifecycleScope
import io.github.suqi8.coui.kmp.window.WindowUserStatementDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import org.koin.compose.koinInject
import top.wkbin.zaomeng.app.shared.App
import top.wkbin.zaomeng.app.update.AppUpdateManager
import top.wkbin.zaomeng.app.update.AppUpdateNotifier
import top.wkbin.zaomeng.app.update.AppUpdatePreferences
import top.wkbin.zaomeng.data.preferences.AppPreferences
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.ContentDisclaimerPreferences
import top.wkbin.zaomeng.data.update.AppUpdateDownloadState
import top.wkbin.zaomeng.data.update.AppUpdateDownloadStatus
import top.wkbin.zaomeng.data.update.AppUpdateUiState
import top.wkbin.zaomeng.feature.update.AppUpdateDialog

/** Android 入口：渲染共享 UI，并驱动平台能力（更新检查/下载、内容声明、通知深链）。 */
class MainActivity : ComponentActivity() {
    private var appUpdateState by mutableStateOf(AppUpdateUiState())
    private var dismissedUpdateVersion by mutableStateOf("")
    private var pendingChaptersRunId by mutableStateOf<String?>(null)
    private var contentDisclaimerAccepted by mutableStateOf(false)
    private var startupUpdateCheckDisabled by mutableStateOf(false)
    private var appUpdateDownloadJob: Job? = null
    private var appUpdateDownloadGeneration = 0L
    private val appUpdateManager by lazy { AppUpdateManager(applicationContext) }
    private val appUpdateNotifier by lazy { AppUpdateNotifier(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 首帧主题需要同步值；读取加上限时兜底，避免 DataStore 首次读盘卡住主线程。
        val initialPreferences = runCatching {
            runBlocking {
                withTimeoutOrNull(400) {
                    GlobalContext.get().get<AppPreferencesRepository>().currentPreferences()
                }
            }
        }.getOrNull() ?: AppPreferences()
        enableEdgeToEdge()
        startupUpdateCheckDisabled = AppUpdatePreferences.isStartupCheckDisabled(this)
        pendingChaptersRunId = intent.getStringExtra("open_run_id")
        setContent {
            val disclaimerPreferences: ContentDisclaimerPreferences = koinInject()
            LaunchedEffect(Unit) {
                contentDisclaimerAccepted = disclaimerPreferences.isAccepted()
            }
            App(
                appUpdateState = appUpdateState,
                onCheckForAppUpdate = { manual -> checkForAppUpdate(manual = manual) },
                onDownloadAppUpdate = ::downloadAppUpdate,
                startupUpdateCheckDisabled = startupUpdateCheckDisabled,
                onStartupUpdateCheckDisabledChange = ::updateStartupUpdateCheckPreference,
                launchChaptersRunId = pendingChaptersRunId,
                onChaptersLaunchConsumed = { pendingChaptersRunId = null },
                initialThemeMode = initialPreferences.themeMode,
                initialThemeSeedColorArgb = initialPreferences.themeSeedColorArgb,
                initialDynamicColorEnabled = initialPreferences.dynamicColorEnabled,
                initialUiScale = initialPreferences.uiScale,
                initialBuiltInBackHandlingEnabled = initialPreferences.builtInBackHandlingEnabled,
                onBuiltInBackHandlingEnabledChange = { enabled ->
                    lifecycleScope.launch {
                        GlobalContext.get().get<AppPreferencesRepository>()
                            .setBuiltInBackHandlingEnabled(enabled)
                    }
                },
                overlay = {
                    if (!contentDisclaimerAccepted) {
                        ContentDisclaimerDialog(
                            onAccept = {
                                contentDisclaimerAccepted = true
                                lifecycleScope.launch { disclaimerPreferences.accept() }
                            },
                            onDecline = { finishAndRemoveTask() },
                        )
                    } else {
                        val update = appUpdateState.availableUpdate
                        if (update != null && dismissedUpdateVersion != update.version) {
                            AppUpdateDialog(
                                update = update,
                                downloadState = appUpdateState.downloadState,
                                onDismiss = { dismissedUpdateVersion = update.version },
                                onDownload = ::downloadAppUpdate,
                            )
                        }
                    }
                },
            )
        }
        if (!startupUpdateCheckDisabled) checkForAppUpdate(manual = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingChaptersRunId = intent.getStringExtra("open_run_id")
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshAppUpdateDownloadState()
    }

    override fun onStop() {
        if (appUpdateState.downloadState.status == AppUpdateDownloadStatus.Downloading) {
            appUpdateDownloadGeneration++
            appUpdateDownloadJob?.cancel()
            appUpdateDownloadJob = null
            appUpdateNotifier.clear()
            appUpdateState = appUpdateState.copy(
                downloadState = AppUpdateDownloadState(version = appUpdateState.downloadState.version),
            )
        }
        super.onStop()
    }

    private fun checkForAppUpdate(manual: Boolean) {
        if (appUpdateState.checking) return
        if (manual) dismissedUpdateVersion = ""
        appUpdateState = appUpdateState.copy(
            checking = true,
            error = "",
            message = if (manual) "正在检查 GitHub Release" else appUpdateState.message,
        )
        lifecycleScope.launch {
            try {
                val update = appUpdateManager.checkForUpdate()
                val downloadState = update?.let { appUpdateManager.refreshDownloadState(it) }
                appUpdateState = AppUpdateUiState(
                    availableUpdate = update,
                    downloadState = downloadState ?: appUpdateState.downloadState,
                    message = if (update == null) "当前已是最新版本。" else "发现 ${update.version} 新版本。",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                appUpdateState = appUpdateState.copy(
                    checking = false,
                    error = if (manual) error.message ?: "检查更新失败。" else "",
                    message = if (manual) "" else appUpdateState.message,
                )
            }
        }
    }

    private fun downloadAppUpdate() {
        val update = appUpdateState.availableUpdate ?: return
        val status = appUpdateState.downloadState.status.takeIf {
            appUpdateState.downloadState.version == update.version
        }
        if (status == AppUpdateDownloadStatus.Downloading) return
        if (status == AppUpdateDownloadStatus.Downloaded) {
            if (!appUpdateManager.installDownloadedUpdate(update)) {
                appUpdateState = appUpdateState.copy(
                    downloadState = AppUpdateDownloadState(version = update.version),
                    error = "安装包不存在或无法打开，请重新下载。",
                )
            }
            return
        }
        appUpdateDownloadJob?.cancel()
        appUpdateNotifier.clear()
        val generation = ++appUpdateDownloadGeneration
        appUpdateDownloadJob = lifecycleScope.launch {
            try {
                appUpdateState = appUpdateState.copy(
                    downloadState = AppUpdateDownloadState(
                        version = update.version,
                        status = AppUpdateDownloadStatus.Downloading,
                    ),
                    error = "",
                )
                appUpdateNotifier.showProgress(update, 0L, -1L)
                val downloadState = appUpdateManager.download(update) { downloadedBytes, totalBytes ->
                    runOnUiThread {
                        if (
                            generation == appUpdateDownloadGeneration &&
                            appUpdateState.downloadState.status == AppUpdateDownloadStatus.Downloading
                        ) {
                            appUpdateState = appUpdateState.copy(
                                downloadState = AppUpdateDownloadState(
                                    version = update.version,
                                    status = AppUpdateDownloadStatus.Downloading,
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                ),
                            )
                            appUpdateNotifier.showProgress(update, downloadedBytes, totalBytes)
                        }
                    }
                }
                if (generation != appUpdateDownloadGeneration) return@launch
                appUpdateState = appUpdateState.copy(
                    downloadState = downloadState,
                    error = if (downloadState.status == AppUpdateDownloadStatus.Failed) {
                        downloadState.failureReason.ifBlank { "更新包下载失败，请重试。" }
                    } else {
                        ""
                    },
                    message = if (downloadState.status == AppUpdateDownloadStatus.Downloaded) {
                        "下载完成，请点击安装。"
                    } else {
                        appUpdateState.message
                    },
                )
                if (downloadState.status == AppUpdateDownloadStatus.Downloaded) {
                    appUpdateNotifier.showCompleted(update)
                } else {
                    appUpdateNotifier.clear()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                appUpdateNotifier.clear()
                appUpdateState = appUpdateState.copy(error = error.message ?: "无法开始下载更新。")
            }
        }
    }

    private fun updateStartupUpdateCheckPreference(disabled: Boolean) {
        AppUpdatePreferences.setStartupCheckDisabled(this, disabled)
        startupUpdateCheckDisabled = disabled
    }

    private fun refreshAppUpdateDownloadState() {
        val update = appUpdateState.availableUpdate ?: return
        lifecycleScope.launch {
            runCatching { appUpdateManager.refreshDownloadState(update) }
                .onSuccess { downloadState ->
                    appUpdateState = appUpdateState.copy(downloadState = downloadState)
                }
        }
    }
}

@Composable
private fun ContentDisclaimerDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    WindowUserStatementDialog(
        show = true,
        title = stringResource(R.string.content_disclaimer_title),
        appName = stringResource(R.string.app_name),
        confirmText = stringResource(R.string.content_disclaimer_accept),
        onConfirm = onAccept,
        exitText = stringResource(R.string.content_disclaimer_decline),
        onExit = onDecline,
        statement = AnnotatedString(stringResource(R.string.content_disclaimer_message)),
    )
}

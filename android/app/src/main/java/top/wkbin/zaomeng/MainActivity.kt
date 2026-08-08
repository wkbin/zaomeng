package top.wkbin.zaomeng

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.ContentDisclaimerPreferences
import top.wkbin.zaomeng.data.update.AppUpdateDownloadState
import top.wkbin.zaomeng.data.update.AppUpdateDownloadStatus
import top.wkbin.zaomeng.data.update.AppUpdateManager
import top.wkbin.zaomeng.data.update.AppUpdateNotifier
import top.wkbin.zaomeng.data.update.AppUpdatePreferences
import top.wkbin.zaomeng.data.update.AppUpdateUiState
import top.wkbin.zaomeng.feature.update.AppUpdateDialog
import top.wkbin.zaomeng.navigation.ZaomengApp
import top.wkbin.zaomeng.ui.theme.MyApplicationTheme

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
        enableEdgeToEdge()
        startupUpdateCheckDisabled = AppUpdatePreferences.isStartupCheckDisabled(this)
        contentDisclaimerAccepted = ContentDisclaimerPreferences.isAccepted(this)
        pendingChaptersRunId = intent.getStringExtra("open_run_id")
        setContent {
            val preferencesRepository: AppPreferencesRepository = koinInject()
            val themeMode = preferencesRepository.themeMode.collectAsStateWithLifecycle(
                initialValue = top.wkbin.zaomeng.data.preferences.ThemeMode.SYSTEM,
            ).value
            MyApplicationTheme(themeMode = themeMode, dynamicColor = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ZaomengApp(
                        appUpdateState = appUpdateState,
                        onCheckForAppUpdate = { checkForAppUpdate(manual = true) },
                        onDownloadAppUpdate = ::downloadAppUpdate,
                        startupUpdateCheckDisabled = startupUpdateCheckDisabled,
                        onStartupUpdateCheckDisabledChange = ::updateStartupUpdateCheckPreference,
                        launchChaptersRunId = pendingChaptersRunId,
                        onChaptersLaunchConsumed = { pendingChaptersRunId = null },
                    )
                    appUpdateState.availableUpdate?.let { update ->
                        if (dismissedUpdateVersion != update.version) {
                            AppUpdateDialog(
                                update = update,
                                downloadState = appUpdateState.downloadState,
                                onDismiss = { dismissedUpdateVersion = update.version },
                                onDownload = ::downloadAppUpdate,
                            )
                        }
                    }
                    if (!contentDisclaimerAccepted) {
                        ContentDisclaimerDialog(
                            onAccept = {
                                ContentDisclaimerPreferences.accept(this@MainActivity)
                                contentDisclaimerAccepted = true
                            },
                            onDecline = { finishAndRemoveTask() },
                        )
                    }
                }
            }
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
        val status = appUpdateState.downloadState.status.takeIf { appUpdateState.downloadState.version == update.version }
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
                    downloadState = AppUpdateDownloadState(version = update.version, status = AppUpdateDownloadStatus.Downloading),
                    error = "",
                )
                appUpdateNotifier.showProgress(update, 0L, -1L)
                val downloadState = appUpdateManager.download(update) { downloadedBytes, totalBytes ->
                    runOnUiThread {
                        if (generation == appUpdateDownloadGeneration && appUpdateState.downloadState.status == AppUpdateDownloadStatus.Downloading) {
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
                    error = if (downloadState.status == AppUpdateDownloadStatus.Failed) "更新包下载失败，请重试。" else "",
                    message = if (downloadState.status == AppUpdateDownloadStatus.Downloaded) "下载完成，请点击安装。" else appUpdateState.message,
                )
                if (downloadState.status == AppUpdateDownloadStatus.Downloaded) appUpdateNotifier.showCompleted(update) else appUpdateNotifier.clear()
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
                .onSuccess { downloadState -> appUpdateState = appUpdateState.copy(downloadState = downloadState) }
        }
    }
}

@Composable
private fun ContentDisclaimerDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.content_disclaimer_title)) },
        text = {
            Text(stringResource(R.string.content_disclaimer_message))
        },
        confirmButton = {
            Button(onClick = onAccept) { Text(stringResource(R.string.content_disclaimer_accept)) }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text(stringResource(R.string.content_disclaimer_decline)) }
        },
    )
}

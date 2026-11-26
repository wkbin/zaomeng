package top.wkbin.zaomeng.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.app.shared.AppMetadata
import top.wkbin.zaomeng.data.update.AppUpdateDownloadStatus
import top.wkbin.zaomeng.data.update.AppUpdateUiState
import top.wkbin.zaomeng.feature.settings.SettingsRow
import top.wkbin.zaomeng.ui.coui.CouiPageScaffold
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.couiPageScroll
import top.wkbin.zaomeng.ui.coui.isWideWindow

@Composable
fun AppUpdateScreen(
    state: AppUpdateUiState,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    startupUpdateCheckDisabled: Boolean,
    onStartupUpdateCheckDisabledChange: (Boolean) -> Unit,
) {
    val update = state.availableUpdate
    val downloadStatus = state.downloadState.status.takeIf { state.downloadState.version == update?.version }
        ?: AppUpdateDownloadStatus.Idle
    CouiPageScaffold(
        title = "检查更新",
        topBarStyle = CouiTopBarStyle.Small,
        onBack = onBack,
        wide = isWideWindow(),
    ) { scope ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(scope.contentPadding)
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .couiPageScroll(scope),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                CardListGroup {
                    item {
                        SettingsRow(
                            title = "当前版本",
                            subtitle = AppMetadata.VERSION_NAME,
                            enabled = false,
                        )
                    }
                    item {
                        SettingsRow(
                            title = "检查更新",
                            subtitle = updateStatusText(state, downloadStatus),
                            value = if (state.checking) "检查中" else "检查",
                            enabled = !state.checking,
                            onClick = onCheck,
                        )
                    }
                    item {
                        SwitchPreference(
                            title = "关闭启动时检查更新",
                            summary = if (startupUpdateCheckDisabled) {
                                "应用启动时不自动检查，可随时手动检查"
                            } else {
                                "应用启动时自动检查新版本"
                            },
                            checked = startupUpdateCheckDisabled,
                            onCheckedChange = onStartupUpdateCheckDisabledChange,
                        )
                    }
                    if (update != null) {
                        item {
                            SettingsRow(
                                title = if (downloadStatus == AppUpdateDownloadStatus.Downloaded) {
                                    "安装 ${update.version}"
                                } else {
                                    "下载 ${update.version}"
                                },
                                subtitle = downloadActionText(downloadStatus),
                                enabled = downloadStatus != AppUpdateDownloadStatus.Downloading,
                                onClick = onDownload,
                            )
                        }
                    }
                }
            }
            if (state.error.isNotBlank()) {
                Text(
                    text = state.error,
                    color = COUITheme.colorScheme.error,
                    style = COUITheme.textStyles.bodyM,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

private fun updateStatusText(state: AppUpdateUiState, downloadStatus: AppUpdateDownloadStatus): String = when {
    state.checking -> "正在检查 GitHub Release"
    state.availableUpdate != null -> "发现 ${state.availableUpdate.version} 新版本，${downloadActionText(downloadStatus)}"
    state.error.isNotBlank() -> "检查失败，点击重试"
    state.message.isNotBlank() -> state.message
    else -> "检查 GitHub Release 中的最新版本"
}

private fun downloadActionText(status: AppUpdateDownloadStatus): String = when (status) {
    AppUpdateDownloadStatus.Idle -> "可下载"
    AppUpdateDownloadStatus.Downloading -> "下载中"
    AppUpdateDownloadStatus.Downloaded -> "安装包已下载，点击安装"
    AppUpdateDownloadStatus.Failed -> "上次下载失败，点击重试"
}

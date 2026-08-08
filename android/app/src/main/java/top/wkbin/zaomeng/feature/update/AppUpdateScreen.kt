package top.wkbin.zaomeng.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.BuildConfig
import top.wkbin.zaomeng.data.update.AppUpdateDownloadStatus
import top.wkbin.zaomeng.data.update.AppUpdateUiState
import top.wkbin.zaomeng.feature.settings.SettingsRow

@OptIn(ExperimentalMaterial3Api::class)
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("检查更新") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column {
                    SettingsRow(title = "当前版本", subtitle = BuildConfig.VERSION_NAME, enabled = false)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsRow(
                        title = "检查更新",
                        subtitle = updateStatusText(state, downloadStatus),
                        value = if (state.checking) "检查中" else "检查",
                        enabled = !state.checking,
                        onClick = onCheck,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    StartupCheckRow(
                        disabled = startupUpdateCheckDisabled,
                        onDisabledChange = onStartupUpdateCheckDisabledChange,
                    )
                    if (update != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsRow(
                            title = if (downloadStatus == AppUpdateDownloadStatus.Downloaded) "安装 ${update.version}" else "下载 ${update.version}",
                            subtitle = downloadActionText(downloadStatus),
                            enabled = downloadStatus != AppUpdateDownloadStatus.Downloading,
                            onClick = onDownload,
                        )
                    }
                }
            }
            if (state.error.isNotBlank()) {
                Text(
                    text = state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun StartupCheckRow(disabled: Boolean, onDisabledChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("关闭启动时检查更新", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (disabled) "应用启动时不自动检查，可随时手动检查" else "应用启动时自动检查新版本",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = disabled, onCheckedChange = onDisabledChange)
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

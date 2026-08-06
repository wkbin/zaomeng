package top.wkbin.zaomeng.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.BuildConfig
import top.wkbin.zaomeng.data.update.AppUpdateDownloadState
import top.wkbin.zaomeng.data.update.AppUpdateDownloadStatus
import top.wkbin.zaomeng.data.update.AppUpdateInfo
import top.wkbin.zaomeng.data.update.formatAppUpdateBytes

@Composable
fun AppUpdateDialog(
    update: AppUpdateInfo,
    downloadState: AppUpdateDownloadState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val status = downloadState.status.takeIf { downloadState.version == update.version }
        ?: AppUpdateDownloadStatus.Idle
    AlertDialog(
        onDismissRequest = { if (status != AppUpdateDownloadStatus.Downloading) onDismiss() },
        title = { Text("发现新版本 ${update.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "当前版本 ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status == AppUpdateDownloadStatus.Downloading) {
                    DownloadProgress(downloadState)
                }
                ReleaseNotes(update.releaseNotes)
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload, enabled = status != AppUpdateDownloadStatus.Downloading) {
                Text(
                    when (status) {
                        AppUpdateDownloadStatus.Downloading -> "下载中"
                        AppUpdateDownloadStatus.Downloaded -> "安装"
                        AppUpdateDownloadStatus.Failed -> "重新下载"
                        AppUpdateDownloadStatus.Idle -> "下载更新"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = status != AppUpdateDownloadStatus.Downloading,
            ) { Text("稍后") }
        },
    )
}

@Composable
private fun DownloadProgress(downloadState: AppUpdateDownloadState) {
    val hasTotal = downloadState.totalBytes > 0L
    if (hasTotal) {
        LinearProgressIndicator(
            progress = { (downloadState.downloadedBytes.toFloat() / downloadState.totalBytes).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Text(
        text = if (hasTotal) {
            val percentage = ((downloadState.downloadedBytes * 100L) / downloadState.totalBytes).coerceIn(0L, 100L)
            "$percentage%  ${formatAppUpdateBytes(downloadState.downloadedBytes)} / ${formatAppUpdateBytes(downloadState.totalBytes)}"
        } else {
            "已下载 ${formatAppUpdateBytes(downloadState.downloadedBytes)}"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReleaseNotes(releaseNotes: String) {
    val lines = releaseNotes.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
        if (lines.isEmpty()) {
            item { Text("本次更新暂未提供详细说明。", style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(lines) { line ->
                Text(
                    text = line.removePrefix("- ").removePrefix("* "),
                    modifier = Modifier.padding(vertical = 3.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

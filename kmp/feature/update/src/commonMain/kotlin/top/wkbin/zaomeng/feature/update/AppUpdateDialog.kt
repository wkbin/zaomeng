package top.wkbin.zaomeng.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.app.shared.AppMetadata
import top.wkbin.zaomeng.data.update.AppUpdateDownloadState
import top.wkbin.zaomeng.data.update.AppUpdateDownloadStatus
import top.wkbin.zaomeng.data.update.AppUpdateInfo
import top.wkbin.zaomeng.data.update.formatAppUpdateBytes
import top.wkbin.zaomeng.ui.coui.CouiWindowAlertDialog

@Composable
fun AppUpdateDialog(
    update: AppUpdateInfo,
    downloadState: AppUpdateDownloadState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    /** 下载按钮文案（桌面端跳转下载页时用“去下载”）。 */
    downloadLabel: String = "下载更新",
) {
    val status = downloadState.status.takeIf { downloadState.version == update.version }
        ?: AppUpdateDownloadStatus.Idle
    // 这个弹窗由 Activity / 桌面窗口根部直接渲染，处在 COUI Scaffold 之外，所以用 Window 版。
    CouiWindowAlertDialog(
        onDismissRequest = { if (status != AppUpdateDownloadStatus.Downloading) onDismiss() },
        title = "发现新版本 ${update.version}",
        confirmText = when (status) {
            AppUpdateDownloadStatus.Downloading -> "下载中"
            AppUpdateDownloadStatus.Downloaded -> "安装"
            AppUpdateDownloadStatus.Failed -> "重新下载"
            AppUpdateDownloadStatus.Idle -> downloadLabel
        },
        onConfirm = onDownload,
        confirmEnabled = status != AppUpdateDownloadStatus.Downloading,
        dismissText = "稍后",
        dismissEnabled = status != AppUpdateDownloadStatus.Downloading,
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "当前版本 ${AppMetadata.VERSION_NAME}",
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
                if (status == AppUpdateDownloadStatus.Downloading) {
                    DownloadProgress(downloadState)
                }
                ReleaseNotes(update.releaseNotes)
            }
        },
    )
}

@Composable
private fun DownloadProgress(downloadState: AppUpdateDownloadState) {
    val hasTotal = downloadState.totalBytes > 0L
    if (hasTotal) {
        LinearProgressIndicator(
            progress = (downloadState.downloadedBytes.toFloat() / downloadState.totalBytes).coerceIn(0f, 1f),
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
        style = COUITheme.textStyles.bodyXS,
        color = COUITheme.colorScheme.labelSecondary,
    )
}

@Composable
private fun ReleaseNotes(releaseNotes: String) {
    val lines = releaseNotes.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
        if (lines.isEmpty()) {
            item { Text("本次更新暂未提供详细说明。", style = COUITheme.textStyles.bodyM) }
        } else {
            items(lines) { line ->
                Text(
                    text = line.removePrefix("- ").removePrefix("* "),
                    modifier = Modifier.padding(vertical = 3.dp),
                    style = COUITheme.textStyles.bodyM,
                )
            }
        }
    }
}

package top.wkbin.zaomeng.data.update

/** 应用更新 UI 模型（跨平台；更新检查/下载由平台服务提供，Android 用 DownloadManager）。 */
data class AppUpdateInfo(
    val version: String,
    val downloadUrl: String,
    val fileName: String,
    val releaseNotes: String,
)

enum class AppUpdateDownloadStatus {
    Idle,
    Downloading,
    Downloaded,
    Failed,
}

data class AppUpdateDownloadState(
    val version: String = "",
    val localPath: String = "",
    val status: AppUpdateDownloadStatus = AppUpdateDownloadStatus.Idle,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
)

data class AppUpdateUiState(
    val checking: Boolean = false,
    val availableUpdate: AppUpdateInfo? = null,
    val downloadState: AppUpdateDownloadState = AppUpdateDownloadState(),
    val message: String = "",
    val error: String = "",
)

internal fun formatAppUpdateBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val oneDecimal = (value * 10).toLong()
    return "${oneDecimal / 10}.${oneDecimal % 10} ${units[unitIndex]}"
}

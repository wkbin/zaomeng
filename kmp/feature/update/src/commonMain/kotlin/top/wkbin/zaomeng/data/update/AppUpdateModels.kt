package top.wkbin.zaomeng.data.update

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
    /** 失败原因（下载中断、大小或 SHA-256 与发布清单不符等）；仅 Failed 时有意义。 */
    val failureReason: String = "",
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

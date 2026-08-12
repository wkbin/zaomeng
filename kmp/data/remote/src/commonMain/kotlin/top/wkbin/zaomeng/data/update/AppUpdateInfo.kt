package top.wkbin.zaomeng.data.update

/** 可由 UI 呈现、由平台负责下载或跳转的发布版本信息。 */
data class AppUpdateInfo(
    val version: String,
    val downloadUrl: String,
    val fileName: String,
    val releaseNotes: String,
)

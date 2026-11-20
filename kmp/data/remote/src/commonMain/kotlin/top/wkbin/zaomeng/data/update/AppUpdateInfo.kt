package top.wkbin.zaomeng.data.update

/** 可由 UI 呈现、由平台负责下载或跳转的发布版本信息。 */
data class AppUpdateInfo(
    val version: String,
    val downloadUrl: String,
    val fileName: String,
    val releaseNotes: String,
    /** 发布清单给出的 SHA-256（小写十六进制）；空串表示发布方/接口没有提供，无法校验。 */
    val sha256: String = "",
    /** 发布清单给出的字节数；0 表示未知。 */
    val sizeBytes: Long = 0L,
)

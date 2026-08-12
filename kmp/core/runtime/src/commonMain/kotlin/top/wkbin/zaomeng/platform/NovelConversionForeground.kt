package top.wkbin.zaomeng.platform

/** 小说章节后台生成前台提示控制器：Android 用前台服务；桌面/iOS 无系统通知（服务端照常生成）。 */
interface NovelConversionForeground {
    /** 返回是否成功启动（Android 前台服务；其余平台恒真）。 */
    fun start(runId: String, sessionId: String, title: String): Boolean

    /** 是否已授予通知权限（Android 检查权限；其余平台恒真）。 */
    fun hasNotificationPermission(): Boolean

    companion object {
        const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    }
}

object NoopNovelConversionForeground : NovelConversionForeground {
    override fun start(runId: String, sessionId: String, title: String): Boolean = true

    override fun hasNotificationPermission(): Boolean = true
}

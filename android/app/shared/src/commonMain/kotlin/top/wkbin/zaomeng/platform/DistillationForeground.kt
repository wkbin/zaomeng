package top.wkbin.zaomeng.platform

/** 蒸馏前台提示控制器：Android 用前台服务展示进度；桌面/iOS 无系统通知（服务端照常蒸馏）。 */
interface DistillationForeground {
    fun start()

    fun stopAll()

    /** 是否已授予通知权限（Android 检查；其余平台恒真）。 */
    fun hasNotificationPermission(): Boolean
}

object NoopDistillationForeground : DistillationForeground {
    override fun start() = Unit

    override fun stopAll() = Unit

    override fun hasNotificationPermission(): Boolean = true
}

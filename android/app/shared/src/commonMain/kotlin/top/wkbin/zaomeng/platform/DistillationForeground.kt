package top.wkbin.zaomeng.platform

/** 蒸馏前台提示控制器：Android 用前台服务展示进度；桌面/iOS 无系统通知（服务端照常蒸馏）。 */
interface DistillationForeground {
    fun start()

    fun stopAll()
}

object NoopDistillationForeground : DistillationForeground {
    override fun start() = Unit

    override fun stopAll() = Unit
}

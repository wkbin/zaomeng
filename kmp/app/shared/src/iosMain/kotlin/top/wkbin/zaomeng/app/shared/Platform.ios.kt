package top.wkbin.zaomeng.app.shared

import platform.Foundation.NSProcessInfo

actual fun platformName(): String = "iOS"

/** iOS 进程环境变量（通常为空，作为可选覆盖来源）。 */
actual fun envVar(name: String): String? =
    NSProcessInfo.processInfo.environment[name]?.toString()

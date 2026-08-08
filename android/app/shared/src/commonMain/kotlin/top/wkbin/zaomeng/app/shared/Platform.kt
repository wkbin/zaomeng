package top.wkbin.zaomeng.app.shared

/** 当前运行平台名称（Android / Desktop）。 */
expect fun platformName(): String

/** 读取环境变量（Android/JVM 均支持）。 */
expect fun envVar(name: String): String?

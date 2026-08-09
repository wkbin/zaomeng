package top.wkbin.zaomeng.app.shared

actual fun platformName(): String = "Desktop"

actual fun envVar(name: String): String? = System.getenv(name)

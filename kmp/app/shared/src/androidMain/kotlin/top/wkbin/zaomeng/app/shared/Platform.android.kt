package top.wkbin.zaomeng.app.shared

actual fun platformName(): String = "Android"

actual fun envVar(name: String): String? = System.getenv(name)

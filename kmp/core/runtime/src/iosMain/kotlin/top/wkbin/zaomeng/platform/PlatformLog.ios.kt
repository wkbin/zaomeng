package top.wkbin.zaomeng.platform

actual object PlatformLog {
    actual fun d(tag: String, message: String) {
        println("[$tag] $message")
    }

    actual fun i(tag: String, message: String) {
        println("[$tag] $message")
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        println("[$tag] $message")
        throwable?.printStackTrace()
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        println("[$tag] $message")
        throwable?.printStackTrace()
    }
}

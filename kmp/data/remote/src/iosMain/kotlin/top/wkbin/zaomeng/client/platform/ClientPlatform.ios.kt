package top.wkbin.zaomeng.client.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSUUID

actual object ClientLog {
    actual fun d(tag: String, message: String) {
        println("[$tag] $message")
    }

    actual fun i(tag: String, message: String) {
        println("[$tag] $message")
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        println("[$tag] WARN: $message")
        throwable?.printStackTrace()
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        println("[$tag] ERROR: $message")
        throwable?.printStackTrace()
    }
}

actual fun clientRandomUuid(): String = NSUUID().UUIDString.lowercase()

actual fun createClientHttpEngine(): HttpClientEngine = Darwin.create()

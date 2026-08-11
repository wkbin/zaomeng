package top.wkbin.zaomeng.client.platform

import io.ktor.client.engine.HttpClientEngine
import okio.ByteString.Companion.toByteString
import kotlin.time.TimeSource

/** Platform services used by the client layer independently of the embedded backend. */
expect object ClientLog {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

expect fun clientRandomUuid(): String

expect fun createClientHttpEngine(): HttpClientEngine

fun clientBase64Encode(bytes: ByteArray): String = bytes.toByteString().base64()

fun clientMonotonicNanos(): Long = TimeSource.Monotonic.markNow().elapsedNow().inWholeNanoseconds

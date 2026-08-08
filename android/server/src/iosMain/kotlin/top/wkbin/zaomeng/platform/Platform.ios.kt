package top.wkbin.zaomeng.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.Path
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileSystemSize
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.UIKit.UIDevice

actual object PlatformLog {
    actual fun d(tag: String, message: String) {
        println("[$tag] $message")
    }

    actual fun i(tag: String, message: String) {
        println("[$tag] $message")
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        println("[$tag] $message")
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        println("[$tag] $message")
        throwable?.printStackTrace()
    }
}

// ------------------------------------------------------------------
// YAML：jvm/android 用 snakeyaml；iOS 待接入 KMP YAML 库（iOS 阶段 TODO）
// ------------------------------------------------------------------
actual fun parseYaml(text: String): Map<String, Any?>? =
    throw NotImplementedError("iOS YAML 解析待接入 KMP YAML 库（iOS 阶段 TODO）")

actual fun dumpYaml(value: Any?): String =
    throw NotImplementedError("iOS YAML 序列化待接入 KMP YAML 库（iOS 阶段 TODO）")

actual fun randomUuid(): String = NSUUID().UUIDString.lowercase()

// ------------------------------------------------------------------
// ZIP：jvm/android 用 java.util.zip；iOS 待接入 KMP zip 实现（iOS 阶段 TODO）
// ------------------------------------------------------------------
actual fun readZipEntries(bytes: ByteArray): List<ZipEntryData> =
    throw NotImplementedError("iOS ZIP 读取待接入 KMP zip 实现（iOS 阶段 TODO）")

actual fun writeZipEntries(entries: List<ZipEntryData>): ByteArray =
    throw NotImplementedError("iOS ZIP 写入待接入 KMP zip 实现（iOS 阶段 TODO）")

actual fun diskSpaceOf(path: Path): DiskSpaceInfo? = try {
    val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(path.toString(), null)
        ?: return null
    val free = attributes[NSFileSystemFreeSize] as? NSNumber ?: return null
    val total = attributes[NSFileSystemSize] as? NSNumber ?: return null
    DiskSpaceInfo(freeBytes = free.longLongValue, totalBytes = total.longLongValue)
} catch (e: Exception) {
    null
}

actual fun systemProperty(name: String): String? = when (name) {
    "java.version" -> "Kotlin/Native (iOS)"
    "os.name" -> "iOS"
    "os.version" -> UIDevice.currentDevice.systemVersion
    "os.arch" -> null
    else -> null
}

actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun createHttpClientEngine(): HttpClientEngine = Darwin.create()

actual fun <T> runBlockingPlatform(block: suspend CoroutineScope.() -> T): T =
    runBlocking(block = block)

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.wkbin.zaomeng.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import it.krzeminski.snakeyaml.engine.kmp.api.Dump
import it.krzeminski.snakeyaml.engine.kmp.api.DumpSettings
import it.krzeminski.snakeyaml.engine.kmp.api.Load
import it.krzeminski.snakeyaml.engine.kmp.common.FlowStyle
import io.github.yuroyami.kitearchive.KiteArchive
import io.github.yuroyami.kitearchive.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.kitearchive.archive.zip.ZipWriter
import io.github.yuroyami.kitearchive.codec.CodecId
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

// YAML：jvm/android 用 snakeyaml（经典版）；iOS 用 snakeyaml-engine-kmp（同一语义的 KMP 移植）。
@Suppress("UNCHECKED_CAST")
actual fun parseYaml(text: String): Map<String, Any?>? =
    runCatching { Load().loadOne(text) }.getOrNull() as? Map<String, Any?>

actual fun dumpYaml(value: Any?): String =
    Dump(DumpSettings(defaultFlowStyle = FlowStyle.BLOCK)).dumpToString(value)

actual fun randomUuid(): String = NSUUID().UUIDString.lowercase()

// ZIP：jvm/android 用 java.util.zip；iOS 用 KiteArchive（纯 Kotlin KMP，STORE/DEFLATE/ZIP64）。
actual fun readZipEntries(bytes: ByteArray): List<ZipEntryData> {
    val reader = KiteArchive.open(ByteArrayRandomAccessSource(bytes))
    return reader.entries()
        .filterNot { it.isDirectory || it.name.endsWith("/") }
        .map { ZipEntryData(it.name, reader.read(it)) }
}

actual fun writeZipEntries(entries: List<ZipEntryData>): ByteArray =
    ZipWriter.write(
        entries.map { ZipWriter.FileSpec(it.name, it.content, CodecId.DEFLATE) },
    )

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

actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.Default

actual fun createHttpClientEngine(): HttpClientEngine = Darwin.create()

actual fun <T> runBlockingPlatform(block: suspend CoroutineScope.() -> T): T =
    runBlocking(block = block)

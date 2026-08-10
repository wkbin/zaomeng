package top.wkbin.zaomeng.platform

import android.os.Build
import android.util.Log
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import it.krzeminski.snakeyaml.engine.kmp.api.Dump
import it.krzeminski.snakeyaml.engine.kmp.api.DumpSettings
import it.krzeminski.snakeyaml.engine.kmp.api.Load
import it.krzeminski.snakeyaml.engine.kmp.common.FlowStyle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.Path
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

actual object PlatformLog {
    actual fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    actual fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }
}

// YAML：Android 用 snakeyaml-engine-kmp（经典 snakeyaml 依赖 java.beans，Android 上不可用）。
@Suppress("UNCHECKED_CAST")
actual fun parseYaml(text: String): Map<String, Any?>? =
    runCatching { Load().loadOne(text) }.getOrNull() as? Map<String, Any?>

actual fun dumpYaml(value: Any?): String =
    Dump(DumpSettings(defaultFlowStyle = FlowStyle.BLOCK)).dumpToString(value)

actual fun randomUuid(): String = UUID.randomUUID().toString()

actual fun readZipEntries(bytes: ByteArray): List<ZipEntryData> {
    val entries = mutableListOf<ZipEntryData>()
    var totalUncompressed = 0L
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var entrySize = 0L
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    entrySize += count
                    totalUncompressed += count
                    require(entrySize <= MAX_ZIP_ENTRY_BYTES) { "ZIP entry is too large" }
                    require(totalUncompressed <= MAX_ZIP_TOTAL_BYTES) { "ZIP archive is too large" }
                    output.write(buffer, 0, count)
                }
                val compressedSize = entry.compressedSize
                if (compressedSize > 0 && entrySize > compressedSize * MAX_ZIP_COMPRESSION_RATIO) {
                    throw IllegalArgumentException("ZIP compression ratio is too high")
                }
                entries += ZipEntryData(entry.name, output.toByteArray())
            }
            zip.closeEntry()
        }
    }
    return entries
}

private const val MAX_ZIP_ENTRY_BYTES = 64L * 1024 * 1024
private const val MAX_ZIP_TOTAL_BYTES = 512L * 1024 * 1024
private const val MAX_ZIP_COMPRESSION_RATIO = 200L

actual fun writeZipEntries(entries: List<ZipEntryData>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        entries.forEach { data ->
            if (data.name.endsWith("/")) {
                zip.putNextEntry(ZipEntry(data.name))
            } else {
                zip.putNextEntry(ZipEntry(data.name))
                zip.write(data.content)
            }
            zip.closeEntry()
        }
    }
    return output.toByteArray()
}

actual fun diskSpaceOf(path: Path): DiskSpaceInfo? = try {
    val file = java.io.File(path.toString())
    DiskSpaceInfo(freeBytes = file.usableSpace, totalBytes = file.totalSpace)
} catch (e: Exception) {
    null
}

actual fun systemProperty(name: String): String? = when (name) {
    "java.version" -> "Android ${Build.VERSION.RELEASE}"
    "os.name" -> "Android"
    "os.arch" -> Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown"
    else -> null
}

actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun createHttpClientEngine(): HttpClientEngine = OkHttp.create()

actual fun <T> runBlockingPlatform(block: suspend CoroutineScope.() -> T): T =
    runBlocking(block = block)

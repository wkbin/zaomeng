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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Path
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream


// YAML：Android 用 snakeyaml-engine-kmp（经典 snakeyaml 依赖 java.beans，Android 上不可用）。
@Suppress("UNCHECKED_CAST")
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

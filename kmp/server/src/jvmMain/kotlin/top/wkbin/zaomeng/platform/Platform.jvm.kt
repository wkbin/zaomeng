package top.wkbin.zaomeng.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.Path
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.LoaderOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

actual object PlatformLog {
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
        System.err.println("[$tag] ERROR: $message")
        throwable?.printStackTrace()
    }
}

private val jvmYamlParser = Yaml(SafeConstructor(LoaderOptions()))
private val jvmYamlDumper = Yaml(
    DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
    }
)

@Suppress("UNCHECKED_CAST")
actual fun parseYaml(text: String): Map<String, Any?>? =
    runCatching { jvmYamlParser.load<Any?>(text) }.getOrNull() as? Map<String, Any?>

actual fun dumpYaml(value: Any?): String = jvmYamlDumper.dump(value)

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
    val file = File(path.toString())
    DiskSpaceInfo(freeBytes = file.usableSpace, totalBytes = file.totalSpace)
} catch (e: Exception) {
    null
}

actual fun systemProperty(name: String): String? = System.getProperty(name)

actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun createHttpClientEngine(): HttpClientEngine = OkHttp.create()

actual fun <T> runBlockingPlatform(block: suspend CoroutineScope.() -> T): T =
    runBlocking(block = block)

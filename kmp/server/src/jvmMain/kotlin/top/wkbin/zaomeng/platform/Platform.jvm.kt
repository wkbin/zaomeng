package top.wkbin.zaomeng.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
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
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.LoaderOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream


private val jvmYamlDumper = Yaml(
    DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
    }
)

@Suppress("UNCHECKED_CAST")
actual fun dumpYaml(value: Any?): String = jvmYamlDumper.dump(value)

actual fun randomUuid(): String = UUID.randomUUID().toString()

internal actual fun forEachZipEntry(bytes: ByteArray, consume: (ZipEntryData) -> Unit) {
    var entryCount = 0
    var totalUncompressed = 0L
    val buffer = ByteArray(16 * 1024)
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            require(++entryCount <= MAX_ZIP_ENTRIES) { "ZIP contains too many entries" }
            val output = if (entry.isDirectory) null else ByteArrayOutputStream()
            var entrySize = 0L
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                entrySize += count
                totalUncompressed += count
                require(entrySize <= MAX_ZIP_ENTRY_BYTES) { "ZIP entry is too large" }
                require(totalUncompressed <= MAX_ZIP_TOTAL_BYTES) { "ZIP archive is too large" }
                output?.write(buffer, 0, count)
            }
            val compressedSize = entry.compressedSize
            require(compressedSize <= 0 || entrySize <= compressedSize * MAX_ZIP_COMPRESSION_RATIO) {
                "ZIP compression ratio is too high"
            }
            if (output != null) consume(ZipEntryData(entry.name, output.toByteArray()))
            zip.closeEntry()
        }
    }
}

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

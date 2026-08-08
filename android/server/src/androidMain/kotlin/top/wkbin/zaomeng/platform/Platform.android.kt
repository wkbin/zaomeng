package top.wkbin.zaomeng.platform

import android.os.Build
import android.util.Log
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

private val androidYamlParser = Yaml(SafeConstructor(LoaderOptions()))
private val androidYamlDumper = Yaml(
    DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
    }
)

@Suppress("UNCHECKED_CAST")
actual fun parseYaml(text: String): Map<String, Any?>? =
    runCatching { androidYamlParser.load<Any?>(text) }.getOrNull() as? Map<String, Any?>

actual fun dumpYaml(value: Any?): String = androidYamlDumper.dump(value)

actual fun randomUuid(): String = UUID.randomUUID().toString()

actual fun readZipEntries(bytes: ByteArray): List<ZipEntryData> {
    val entries = mutableListOf<ZipEntryData>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) {
                entries += ZipEntryData(entry.name, zip.readBytes())
            }
            zip.closeEntry()
        }
    }
    return entries
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

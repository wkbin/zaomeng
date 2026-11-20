package top.wkbin.zaomeng.platform

import io.github.yuroyami.kitearchive.KiteArchive
import io.github.yuroyami.kitearchive.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.kitearchive.archive.zip.ZipWriter
import io.github.yuroyami.kitearchive.codec.CodecId
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import top.wkbin.zaomeng.data.api.ImportRunPackageRequest
import top.wkbin.zaomeng.ktor.services.RunPackageService
import top.wkbin.zaomeng.ktor.services.StorageService
import java.util.Base64
import kotlin.io.path.createTempDirectory
import okio.Path.Companion.toPath
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 验证 KiteArchive 的 ZIP 读写——iOS 端 readZipEntries/writeZipEntries actual 使用同一 API，
 * 本测试在 JVM 上跑通，并做与 java.util.zip 的双向互操作。
 */
class KiteArchiveZipTest {
    private fun readAll(bytes: ByteArray): List<ZipEntryData> {
        val reader = KiteArchive.open(ByteArrayRandomAccessSource(bytes))
        return reader.entries()
            .filterNot { it.isDirectory || it.name.endsWith("/") }
            .map { ZipEntryData(it.name, reader.read(it)) }
    }

    @Test
    fun `zip write and read round trip`() {
        val entries = listOf(
            ZipEntryData("run_manifest.json", """{"run_id":"run-1"}""".encodeToByteArray()),
            ZipEntryData("dialogue/sessions/dlg-1/session_manifest.json", "[]".encodeToByteArray()),
            ZipEntryData("人物卡/林晚.md", "林晚".encodeToByteArray()),
        )

        val bytes = ZipWriter.write(
            entries.map { ZipWriter.FileSpec(it.name, it.content, CodecId.DEFLATE) },
        )
        val readBack = readAll(bytes)

        assertEquals(entries.map { it.name }, readBack.map { it.name })
        entries.zip(readBack).forEach { (expected, actual) ->
            assertContentEquals(expected.content, actual.content)
        }
    }

    @Test
    fun `reads zip produced by java util zip`() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("run_manifest.json"))
            zip.write("""{"run_id":"run-1"}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("world_memory.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }

        val readBack = readAll(output.toByteArray())

        assertEquals(listOf("run_manifest.json", "world_memory.json"), readBack.map { it.name })
        assertEquals("""{"run_id":"run-1"}""", readBack[0].content.decodeToString())
        assertEquals("{}", readBack[1].content.decodeToString())
    }

    @Test
    fun `visits entries in order and stops on a consumer failure`() {
        val bytes = writeZipEntries(
            listOf(ZipEntryData("first.txt", byteArrayOf(1)), ZipEntryData("second.txt", byteArrayOf(2))),
        )
        val visited = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            forEachZipEntry(bytes) { entry ->
                visited += entry.name
                error("stop")
            }
        }
        assertEquals(listOf("first.txt"), visited)
        assertEquals(listOf("first.txt", "second.txt"), readZipEntries(bytes).map { it.name })
    }

    @Test
    fun `directory headers count toward the ZIP entry limit`() {
        val bytes = writeZipEntries(List(MAX_ZIP_ENTRIES + 1) { ZipEntryData("dir-$it/", ByteArray(0)) })
        assertFailsWith<IllegalArgumentException> { forEachZipEntry(bytes) { error("Unexpected file") } }
    }

    @Test
    fun `package import rejects archive paths that Windows would alias`() {
        val dir = createTempDirectory("zaomeng-package-path")
        try {
            val storage = StorageService(dir.toString().toPath())
            val importer = RunPackageService(storage)
            for (name in listOf("run/trailing.", "run/trailing ", "run/CON .txt")) {
                val bytes = writeZipEntries(listOf(ZipEntryData(name, byteArrayOf(1))))
                val request = ImportRunPackageRequest(
                    filename = "book.zip",
                    contentBase64 = Base64.getEncoder().encodeToString(bytes),
                )
                val error = assertFailsWith<IllegalArgumentException> { importer.importPackage(request) }
                assertEquals(
                    if (name.endsWith(".txt")) "reserved archive file name" else "invalid archive file name",
                    error.message,
                )
                assertEquals(emptyList(), storage.listRunIds())
                assertEquals(emptyList(), storage.listFiles(storage.getStorageRoot() / "tmp-imports"))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `writes zip readable by java util zip`() {
        val bytes = ZipWriter.write(
            listOf(ZipWriter.FileSpec("a.txt", "hello".encodeToByteArray(), CodecId.STORE)),
        )
        val names = mutableListOf<String>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                zip.closeEntry()
            }
        }
        assertEquals(listOf("a.txt"), names)
    }
}

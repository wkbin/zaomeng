package top.wkbin.zaomeng.platform

import io.github.yuroyami.kitearchive.KiteArchive
import io.github.yuroyami.kitearchive.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.kitearchive.archive.zip.ZipWriter
import io.github.yuroyami.kitearchive.codec.CodecId
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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

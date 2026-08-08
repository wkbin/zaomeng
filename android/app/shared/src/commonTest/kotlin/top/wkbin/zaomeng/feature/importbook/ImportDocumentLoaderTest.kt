package top.wkbin.zaomeng.feature.importbook

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImportDocumentLoaderTest {
    @Test
    fun `utf8 novel is kept and missing extension is restored`() {
        val text = "黛玉望着窗外。"

        val document = ImportDocumentLoader.prepareImportDocument(
            displayName = "content://downloads/红楼梦",
            bytes = text.encodeToByteArray(),
            expectedKind = ImportDocumentKind.NovelText,
        )

        assertEquals("红楼梦.txt", document.fileName)
        assertEquals("UTF-8", document.sourceEncoding)
        assertEquals(text, document.bytes.decodeToString())
    }

    @Test
    fun `novel document reports normalized character and sentence counts`() {
        val document = ImportDocumentLoader.prepareImportDocument(
            displayName = "sample.txt",
            bytes = "One. Two! Three;\nFour?".encodeToByteArray(),
            expectedKind = ImportDocumentKind.NovelText,
        )

        assertEquals(22, document.charCount)
        assertEquals(4, document.sentenceCount)
    }

    @Test
    fun `utf16 novel is normalized to utf8`() {
        val text = "宝玉说道：好。"
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16LeBytes(text)

        val document = ImportDocumentLoader.prepareImportDocument(
            displayName = "红楼梦.txt",
            bytes = utf16,
            expectedKind = ImportDocumentKind.NovelText,
        )

        assertEquals("UTF-16 LE", document.sourceEncoding)
        assertEquals(text, document.bytes.decodeToString())
    }

    @Test
    fun `gb18030 novel is normalized to utf8`() {
        val text = "悟空举起金箍棒。"
        val gb18030 = byteArrayOf(
            0xCE.toByte(), 0xF2.toByte(), 0xBF.toByte(), 0xD5.toByte(),
            0xBE.toByte(), 0xD9.toByte(), 0xC6.toByte(), 0xF0.toByte(),
            0xBD.toByte(), 0xF0.toByte(), 0xB9.toByte(), 0xBF.toByte(),
            0xB0.toByte(), 0xF4.toByte(), 0xA1.toByte(), 0xA3.toByte(),
        )

        val document = ImportDocumentLoader.prepareImportDocument(
            displayName = "西游记.txt",
            bytes = gb18030,
            expectedKind = ImportDocumentKind.NovelText,
        )

        assertEquals("GB18030", document.sourceEncoding)
        assertEquals(text, document.bytes.decodeToString())
    }

    @Test
    fun `epub is extracted into normalized utf8 novel text`() {
        val zip = storeZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "OEBPS/chapter-1.xhtml" to
                    "<html><body><h1>第一章</h1><p>宝玉来了。</p></body></html>".encodeToByteArray(),
            ),
        )

        val document = ImportDocumentLoader.prepareImportDocument(
            displayName = "红楼梦.epub",
            bytes = zip,
            expectedKind = ImportDocumentKind.NovelText,
        )

        assertEquals("红楼梦.txt", document.fileName)
        assertEquals("EPUB", document.sourceEncoding)
        assertEquals("第一章\n宝玉来了。", document.bytes.decodeToString())
    }

    @Test
    fun `zip package is accepted even when provider omits extension`() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)

        val document = ImportDocumentLoader.prepareImportDocument(
            displayName = "backup",
            bytes = zip,
            expectedKind = ImportDocumentKind.RunPackage,
        )

        assertEquals("backup.zaomeng-run.zip", document.fileName)
        assertContentEquals(zip, document.bytes)
    }

    @Test
    fun `zip selected as novel gives actionable error`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ImportDocumentLoader.prepareImportDocument(
                displayName = "book.txt",
                bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04),
                expectedKind = ImportDocumentKind.NovelText,
            )
        }

        assertEquals("这个文件是压缩包，请使用“导入书卷包”。", error.message)
    }

    @Test
    fun `plain text selected as package is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ImportDocumentLoader.prepareImportDocument(
                displayName = "book.zaomeng-run.zip",
                bytes = "not a zip".encodeToByteArray(),
                expectedKind = ImportDocumentKind.RunPackage,
            )
        }

        assertEquals("这不是有效的 ZIP 书卷包，请选择导出的 .zaomeng-run.zip 文件。", error.message)
    }

    @Test
    fun `malformed utf16 bom gives readable encoding error`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ImportDocumentLoader.prepareImportDocument(
                displayName = "broken.txt",
                bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41),
                expectedKind = ImportDocumentKind.NovelText,
            )
        }

        assertEquals("无法识别 TXT 编码，请先把文件另存为 UTF-8 后重试。", error.message)
    }

    @Test
    fun `package limit is the same 64 MB client cap`() {
        assertEquals(64 * 1024 * 1024, ImportDocumentLoader.MAX_PACKAGE_BYTES)
        assertEquals(
            "书卷包过大，Android 客户端当前最多支持 64 MB 的压缩文件。",
            ImportDocumentLoader.fileTooLargeMessage(
                ImportDocumentKind.RunPackage,
                ImportDocumentLoader.MAX_PACKAGE_BYTES,
            ),
        )
    }

    private fun utf16LeBytes(text: String): ByteArray = buildList {
        for (char in text) {
            val code = char.code
            add((code and 0xFF).toByte())
            add(((code shr 8) and 0xFF).toByte())
        }
    }.toByteArray()

    /** 手工构造仅 STORE 方法的最小 zip（测试夹具，避免依赖平台 zip 库）。 */
    private fun storeZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val local = ArrayList<Byte>()
        val central = ArrayList<Byte>()
        var offset = 0
        entries.forEach { (name, content) ->
            val nameBytes = name.encodeToByteArray()
            val crc = crc32(content)
            val header = buildList {
                addAll(intBytes(0x04034B50))
                addAll(shortBytes(20)) // version needed
                addAll(shortBytes(0)) // flags
                addAll(shortBytes(0)) // method: store
                addAll(shortBytes(0)) // time
                addAll(shortBytes(0)) // date
                addAll(intBytes(crc))
                addAll(intBytes(content.size))
                addAll(intBytes(content.size))
                addAll(shortBytes(nameBytes.size))
                addAll(shortBytes(0)) // extra len
            }
            local += header
            local += nameBytes.toList()
            local += content.toList()

            val directoryEntry = buildList {
                addAll(intBytes(0x02014B50))
                addAll(shortBytes(20)) // version made by
                addAll(shortBytes(20)) // version needed
                addAll(shortBytes(0)) // flags
                addAll(shortBytes(0)) // method
                addAll(shortBytes(0)) // time
                addAll(shortBytes(0)) // date
                addAll(intBytes(crc))
                addAll(intBytes(content.size))
                addAll(intBytes(content.size))
                addAll(shortBytes(nameBytes.size))
                addAll(shortBytes(0)) // extra
                addAll(shortBytes(0)) // comment
                addAll(shortBytes(0)) // disk
                addAll(shortBytes(0)) // internal attrs
                addAll(intBytes(0)) // external attrs
                addAll(intBytes(offset))
            }
            central += directoryEntry
            central += nameBytes.toList()
            offset += header.size + nameBytes.size + content.size
        }
        val centralOffset = offset
        val endOfCentral = buildList {
            addAll(intBytes(0x06054B50))
            addAll(shortBytes(0)) // disk
            addAll(shortBytes(0)) // cd disk
            addAll(shortBytes(entries.size))
            addAll(shortBytes(entries.size))
            addAll(intBytes(central.size))
            addAll(intBytes(centralOffset))
            addAll(shortBytes(0)) // comment len
        }
        return (local + central + endOfCentral).toByteArray()
    }

    private fun crc32(bytes: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (byte in bytes) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
        }
        return crc.inv()
    }

    private fun intBytes(value: Int): List<Byte> = listOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun shortBytes(value: Int): List<Byte> = listOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )
}

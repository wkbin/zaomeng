package top.wkbin.zaomeng.feature.importbook

import top.wkbin.zaomeng.platform.decodeGb18030Strict
import top.wkbin.zaomeng.platform.readZipFileEntries

/** 导入文档分类（ZIP 魔数 -> 书卷包；其余按纯文本小说处理）。 */
enum class ImportDocumentKind {
    NovelText,
    RunPackage,
}

data class ImportDocument(
    val fileName: String,
    val bytes: ByteArray,
    val kind: ImportDocumentKind,
    val sourceEncoding: String = "",
    val charCount: Int = 0,
    val sentenceCount: Int = 0,
)

internal data class TextStatistics(
    val charCount: Int,
    val sentenceCount: Int,
)

internal fun textStatistics(text: String): TextStatistics {
    val normalized = text.replace("\r\n", "\n").trim()
    return TextStatistics(
        charCount = normalized.length,
        sentenceCount = normalized
            .split(Regex("[。！？!?;；.\\n]+"))
            .count { it.trim().isNotEmpty() },
    )
}

/** 导入文档构造：TXT/EPUB 编码识别与正文抽取、书卷包校验（三端一致）。 */
object ImportDocumentLoader {
    internal const val MAX_NOVEL_BYTES = 24 * 1024 * 1024
    internal const val MAX_PACKAGE_BYTES = 64 * 1024 * 1024
    private const val MAX_EPUB_UNCOMPRESSED_BYTES = 48 * 1024 * 1024
    private const val MAX_EPUB_CONTENT_ENTRIES = 2_000

    fun prepareImportDocument(
        displayName: String,
        bytes: ByteArray,
        expectedKind: ImportDocumentKind,
    ): ImportDocument {
        if (bytes.isEmpty()) {
            throw IllegalArgumentException("所选文件是空的。")
        }
        return when (expectedKind) {
            ImportDocumentKind.NovelText -> prepareNovel(displayName, bytes)
            ImportDocumentKind.RunPackage -> preparePackage(displayName, bytes)
        }
    }

    private fun prepareNovel(displayName: String, bytes: ByteArray): ImportDocument {
        val sanitizedName = sanitizeDisplayName(displayName)
        if (sanitizedName.endsWith(".epub", ignoreCase = true)) {
            if (!bytes.hasZipSignature()) {
                throw IllegalArgumentException("这不是有效的 EPUB 文件。")
            }
            val text = extractEpubText(bytes)
            if (text.isBlank()) {
                throw IllegalArgumentException("EPUB 中没有可导入的正文。")
            }
            val fileName = sanitizedName.removeSuffixIgnoreCase(".epub").ifBlank { "novel" } + ".txt"
            return novelDocument(fileName, text, "EPUB")
        }
        if (bytes.hasZipSignature()) {
            throw IllegalArgumentException("这个文件是压缩包，请使用“导入书卷包”。")
        }
        val decoded = decodeNovelText(bytes)
        if (decoded.text.isBlank()) {
            throw IllegalArgumentException("所选 TXT 没有可导入的正文。")
        }
        if ('\u0000' in decoded.text) {
            throw IllegalArgumentException("无法识别 TXT 编码，请先把文件另存为 UTF-8 后重试。")
        }
        val fileName = sanitizedName.ifBlank { "novel.txt" }.let { name ->
            if (name.endsWith(".txt", ignoreCase = true)) name else "$name.txt"
        }
        return novelDocument(fileName, decoded.text.removePrefix("\uFEFF"), decoded.encoding)
    }

    private fun preparePackage(displayName: String, bytes: ByteArray): ImportDocument {
        if (!bytes.hasZipSignature()) {
            throw IllegalArgumentException("这不是有效的 ZIP 书卷包，请选择导出的 .zaomeng-run.zip 文件。")
        }
        val sanitizedName = sanitizeDisplayName(displayName)
        val fileName = when {
            sanitizedName.isBlank() -> "imported.zaomeng-run.zip"
            sanitizedName.endsWith(".zip", ignoreCase = true) -> sanitizedName
            else -> "$sanitizedName.zaomeng-run.zip"
        }
        return ImportDocument(
            fileName = fileName,
            bytes = bytes,
            kind = ImportDocumentKind.RunPackage,
        )
    }

    private fun novelDocument(fileName: String, text: String, encoding: String): ImportDocument {
        val statistics = textStatistics(text)
        return ImportDocument(
            fileName = fileName,
            bytes = text.encodeToByteArray(),
            kind = ImportDocumentKind.NovelText,
            sourceEncoding = encoding,
            charCount = statistics.charCount,
            sentenceCount = statistics.sentenceCount,
        )
    }

    private fun decodeNovelText(bytes: ByteArray): DecodedText {
        if (bytes.startsWith(0xEF, 0xBB, 0xBF)) {
            return DecodedText(
                decodeUtf8Strict(bytes.copyOfRange(3, bytes.size))
                    ?: throw unsupportedEncoding(),
                "UTF-8",
            )
        }
        if (bytes.startsWith(0xFF, 0xFE)) {
            return DecodedText(
                decodeUtf16Strict(bytes.copyOfRange(2, bytes.size), littleEndian = true)
                    ?: throw unsupportedEncoding(),
                "UTF-16 LE",
            )
        }
        if (bytes.startsWith(0xFE, 0xFF)) {
            return DecodedText(
                decodeUtf16Strict(bytes.copyOfRange(2, bytes.size), littleEndian = false)
                    ?: throw unsupportedEncoding(),
                "UTF-16 BE",
            )
        }
        decodeUtf8Strict(bytes)?.let { return DecodedText(it, "UTF-8") }
        decodeGb18030Strict(bytes)?.let { return DecodedText(it, "GB18030") }
        throw unsupportedEncoding()
    }

    /** UTF-8 严格校验：解码后按 UTF-8 重新编码必须逐字节一致（无效序列会变成 U+FFFD 导致不一致）。 */
    private fun decodeUtf8Strict(bytes: ByteArray): String? {
        val decoded = bytes.decodeToString()
        return decoded.takeIf { it.encodeToByteArray().contentEquals(bytes) }
    }

    /** UTF-16 严格校验（含代理对合法性）。 */
    private fun decodeUtf16Strict(bytes: ByteArray, littleEndian: Boolean): String? {
        if (bytes.size % 2 != 0) return null
        val units = CharArray(bytes.size / 2)
        for (i in units.indices) {
            val first = bytes[i * 2].toInt() and 0xFF
            val second = bytes[i * 2 + 1].toInt() and 0xFF
            units[i] = if (littleEndian) {
                ((second shl 8) or first).toChar()
            } else {
                ((first shl 8) or second).toChar()
            }
        }
        var index = 0
        while (index < units.size) {
            val code = units[index].code
            when {
                code in 0xD800..0xDBFF -> {
                    if (index + 1 >= units.size) return null
                    val next = units[index + 1].code
                    if (next !in 0xDC00..0xDFFF) return null
                    index += 2
                }
                code in 0xDC00..0xDFFF -> return null
                else -> index++
            }
        }
        return units.concatToString()
    }

    private fun extractEpubText(bytes: ByteArray): String {
        val sections = mutableListOf<String>()
        var totalUncompressed = 0
        for (entry in readZipFileEntries(bytes)) {
            val name = entry.name.lowercase()
            if (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) {
                if (sections.size >= MAX_EPUB_CONTENT_ENTRIES) {
                    throw IllegalArgumentException("EPUB 章节过多，无法安全导入。")
                }
                totalUncompressed += entry.content.size
                if (totalUncompressed > MAX_EPUB_UNCOMPRESSED_BYTES) {
                    throw IllegalArgumentException("EPUB 解压后的正文过大，当前最多支持 48 MB。")
                }
                val text = htmlToText(entry.content.decodeToString())
                if (text.isNotBlank()) sections += text
            }
        }
        return sections.joinToString("\n\n").trim()
    }

    private fun htmlToText(value: String): String = value
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|h[1-6]|li|blockquote|section|article)>"), "\n")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    internal fun fileTooLargeMessage(kind: ImportDocumentKind, maxBytes: Int): String = when (kind) {
        ImportDocumentKind.NovelText ->
            "TXT 或 EPUB 小说过大，Android 客户端当前最多支持 ${maxBytes / 1024 / 1024} MB。"
        ImportDocumentKind.RunPackage ->
            "书卷包过大，Android 客户端当前最多支持 ${maxBytes / 1024 / 1024} MB 的压缩文件。"
    }

    private fun unsupportedEncoding(): IllegalArgumentException =
        IllegalArgumentException("无法识别 TXT 编码，请先把文件另存为 UTF-8 后重试。")

    private fun sanitizeDisplayName(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

    private fun ByteArray.hasZipSignature(): Boolean =
        startsWith(0x50, 0x4B, 0x03, 0x04) ||
            startsWith(0x50, 0x4B, 0x05, 0x06) ||
            startsWith(0x50, 0x4B, 0x07, 0x08)

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index].toInt() and 0xFF == prefix[index] }

    private data class DecodedText(val text: String, val encoding: String)
}

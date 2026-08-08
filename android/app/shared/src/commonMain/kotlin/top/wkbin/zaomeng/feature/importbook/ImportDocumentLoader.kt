package top.wkbin.zaomeng.feature.importbook

/** 导入文档分类（ZIP 魔数 -> 书卷包；其余按纯文本小说处理；编码检测留 TODO，先按 UTF-8）。 */
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

private const val MAX_NOVEL_BYTES = 50 * 1024 * 1024
private const val MAX_PACKAGE_BYTES = 100 * 1024 * 1024

/** 由选择器给出的文件名与字节内容构造导入文档（含类型校验与大小上限）。 */
fun classifyDocument(
    fileName: String,
    bytes: ByteArray,
    expectedKind: ImportDocumentKind,
): ImportDocument {
    val isZip = bytes.size >= 4 &&
        bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    val kind = if (isZip) ImportDocumentKind.RunPackage else ImportDocumentKind.NovelText
    require(kind == expectedKind) { "文件类型与所选导入方式不匹配。" }
    val limit = if (kind == ImportDocumentKind.RunPackage) MAX_PACKAGE_BYTES else MAX_NOVEL_BYTES
    require(bytes.size <= limit) { "文件过大，无法安全导入。" }
    val text = if (kind == ImportDocumentKind.NovelText) bytes.decodeToString() else ""
    val stats = textStatistics(text)
    return ImportDocument(
        fileName = fileName,
        bytes = bytes,
        kind = kind,
        sourceEncoding = "UTF-8",
        charCount = stats.charCount,
        sentenceCount = stats.sentenceCount,
    )
}

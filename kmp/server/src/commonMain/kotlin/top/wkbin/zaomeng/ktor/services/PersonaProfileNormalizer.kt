package top.wkbin.zaomeng.ktor.services

internal data class PersonaProfileNormalization(
    val markdown: String,
    val clearedFields: Set<String>,
    val removedEvidenceCount: Int,
)

/** Deterministically enforces profile output rules that models occasionally ignore. */
internal object PersonaProfileNormalizer {
    private val EVIDENCE_ID = Regex("S\\d{6}")
    private val PLACEHOLDERS = setOf(
        "留空", "空白", "不详", "信息不足", "暂无", "未知", "资料不足", "证据不足", "待补充",
        "无证据", "原文未提及", "未提及", "无相关信息", "null", "none", "n/a", "na", "insufficient",
        "-", "—", "…", "...",
    )

    fun normalize(
        markdown: String,
        allowedEvidenceIds: Set<String>,
        maxEvidenceIds: Int,
    ): PersonaProfileNormalization {
        val clearedFields = linkedSetOf<String>()
        var removedEvidenceCount = 0
        val normalized = markdown.lines().joinToString("\n") { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("- ") || !line.contains(":")) return@joinToString rawLine
            val (rawKey, rawValue) = line.removePrefix("- ").split(":", limit = 2)
            val key = rawKey.trim()
            if (key.isEmpty()) return@joinToString rawLine
            val value = rawValue.trim()
            val normalizedValue = when {
                key == "evidence_source" -> {
                    val referenced = EVIDENCE_ID.findAll(value).map { it.value }.toList()
                    val retained = referenced.asSequence()
                        .filter(allowedEvidenceIds::contains)
                        .distinct()
                        .take(maxEvidenceIds)
                        .toList()
                    removedEvidenceCount += referenced.size - retained.size
                    retained.joinToString("；")
                }
                isPlaceholder(value) -> {
                    clearedFields += key
                    ""
                }
                else -> value
            }
            val indentation = rawLine.takeWhile(Char::isWhitespace)
            "$indentation- $key:${if (normalizedValue.isEmpty()) "" else " $normalizedValue"}"
        }.trim()
        return PersonaProfileNormalization(normalized, clearedFields, removedEvidenceCount)
    }

    fun normalizeFieldValue(value: String): String = value.trim().takeUnless(::isPlaceholder).orEmpty()

    fun isPlaceholder(value: String): Boolean {
        val normalized = value.trim()
            .trim('`', '"', '\'', '“', '”', '‘', '’', '[', ']', '（', '）', '(', ')')
            .trim()
            .lowercase()
        return normalized in PLACEHOLDERS ||
            (normalized.startsWith("留空") && normalized.length <= 12)
    }
}

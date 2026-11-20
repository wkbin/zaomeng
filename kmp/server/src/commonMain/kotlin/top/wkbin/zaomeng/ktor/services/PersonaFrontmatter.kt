package top.wkbin.zaomeng.ktor.services

private val frontmatterDelimiter = Regex("^---[ \\t]*\\r?$", RegexOption.MULTILINE)

internal fun String.splitPersonaFrontmatter(): Pair<String, String>? {
    if (!startsWith("---")) return null
    val start = frontmatterDelimiter.find(this)?.takeIf { it.range.first == 0 } ?: return null
    val end = frontmatterDelimiter.find(this, start.range.last + 1) ?: return null
    return substring(start.range.last + 1, end.range.first) to substring(end.range.last + 1)
}

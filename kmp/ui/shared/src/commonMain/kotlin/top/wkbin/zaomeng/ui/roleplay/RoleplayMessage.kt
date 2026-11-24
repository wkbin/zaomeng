package top.wkbin.zaomeng.ui.roleplay

internal sealed interface RoleplayBlock {
    data class Prose(val text: String) : RoleplayBlock
    data class Status(val title: String, val text: String) : RoleplayBlock
}

internal enum class RoleplayTone { Plain, Narration, Dialogue }

internal data class RoleplaySpan(val text: String, val tone: RoleplayTone)

private val roleplayTag = Regex(
    """(?:\\?<|&lt;)(/?(?:StatusBlocks|details|summary))(?:\\?>|&gt;)""",
    RegexOption.IGNORE_CASE,
)
private val continuedLine = Regex("""\\\r?\n""")
private val detailsPattern = Regex("""(?is)<details>(.*?)</details>""")
private val summaryPattern = Regex("""(?is)^\s*<summary>(.*?)</summary>""")
private val openBlockPattern = Regex("""<(statusblocks|details)>""")
private val proseMarker = Regex("""“[^”\n]+”|「[^」\n]+」|『[^』\n]+』|"[^"\n]+"|(?<!\*)\*(?!\*)([^*\n]+)\*(?!\*)""")
private val paragraphBreak = Regex("""\r?\n(?:[ \t]*\r?\n)+""")

internal fun roleplayParagraphs(text: String): List<String> = text.split(paragraphBreak)

internal fun parseRoleplayMessage(message: String, streaming: Boolean = false): List<RoleplayBlock> {
    val text = continuedLine.replace(message, "\n").let { normalized ->
        roleplayTag.replace(normalized) { "<${it.groupValues[1].lowercase()}>" }
    }
    return buildList {
        var cursor = 0
        while (cursor < text.length) {
            val match = openBlockPattern.find(text, cursor)
            if (match == null) {
                val prose = text.substring(cursor).trim()
                if (prose.isNotEmpty()) add(RoleplayBlock.Prose(prose))
                break
            }
            val prose = text.substring(cursor, match.range.first).trim()
            if (prose.isNotEmpty()) add(RoleplayBlock.Prose(prose))
            val name = match.groupValues[1]
            val start = match.range.last + 1
            val close = "</$name>"
            val end = text.indexOf(close, start)
            if (end < 0 && streaming) break
            val body = text.substring(start, if (end >= 0) end else text.length)
            if (name == "details") {
                addStatus(body)
            } else {
                var statusCursor = 0
                detailsPattern.findAll(body).forEach { detail ->
                    addStatus(body.substring(statusCursor, detail.range.first))
                    addStatus(detail.groupValues[1])
                    statusCursor = detail.range.last + 1
                }
                addStatus(body.substring(statusCursor).trimStart().removePrefix("<details>"))
            }
            cursor = if (end >= 0) end + close.length else text.length
        }
    }
}

private fun MutableList<RoleplayBlock>.addStatus(source: String) {
    val summary = summaryPattern.find(source)
    val title = summary?.groupValues?.get(1)?.replace('\n', ' ')?.trim().orEmpty().ifBlank { "状态栏" }
    val text = source.substring(summary?.range?.last?.plus(1) ?: 0).trim()
    val lines = text.lines()
    val fence = "\u0060\u0060\u0060"
    val body = if (lines.size >= 2 && lines.first().fenceLine().startsWith(fence) && lines.last().fenceLine() == fence) {
        lines.drop(1).dropLast(1).joinToString("\n")
    } else text
    if (body.isNotBlank()) add(RoleplayBlock.Status(title, body))
}

private fun String.fenceLine(): String = trim().replace("\\\u0060", "\u0060")

internal fun roleplaySpans(text: String): List<RoleplaySpan> {
    val markers = proseMarker.findAll(text).toList()
    if (markers.isEmpty()) return listOf(RoleplaySpan(text, RoleplayTone.Plain))
    val defaultTone = if (markers.any { it.groups[1] == null }) RoleplayTone.Narration else RoleplayTone.Plain
    return buildList {
        var cursor = 0
        for (marker in markers) {
            if (marker.range.first > cursor) {
                add(RoleplaySpan(text.substring(cursor, marker.range.first), defaultTone))
            }
            val action = marker.groups[1]?.value
            add(RoleplaySpan(action ?: marker.value, if (action == null) RoleplayTone.Dialogue else RoleplayTone.Narration))
            cursor = marker.range.last + 1
        }
        if (cursor < text.length) add(RoleplaySpan(text.substring(cursor), defaultTone))
    }
}

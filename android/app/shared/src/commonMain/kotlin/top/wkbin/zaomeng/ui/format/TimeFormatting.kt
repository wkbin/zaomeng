package top.wkbin.zaomeng.ui.format

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

private val isoTimestampPattern = Regex(
    """^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?$""",
)

/** 把 ISO-8601 时间戳显示为设备时区的 "yyyy-MM-dd HH:mm"；无法解析时返回 [fallback]。 */
fun String.toLocalDateTimeDisplay(fallback: String = "时间未记录"): String {
    val match = isoTimestampPattern.matchEntire(trim()) ?: return fallback
    val canonical = runCatching { canonicalIso(match.groupValues) }.getOrNull() ?: return fallback
    val local = runCatching {
        Instant.parse(canonical).toLocalDateTime(TimeZone.currentSystemDefault())
    }.getOrNull() ?: return fallback
    return buildString {
        append(local.year.toString().padStart(4, '0'))
        append('-')
        append(local.month.number.toString().padStart(2, '0'))
        append('-')
        append(local.day.toString().padStart(2, '0'))
        append(' ')
        append(local.hour.toString().padStart(2, '0'))
        append(':')
        append(local.minute.toString().padStart(2, '0'))
    }
}

private fun canonicalIso(groups: List<String>): String = buildString {
    append(groups[1].padStart(4, '0'))
    append('-')
    append(groups[2])
    append('-')
    append(groups[3])
    append('T')
    append(groups[4])
    append(':')
    append(groups[5])
    append(':')
    append(groups[6].ifBlank { "00" })
    if (groups[7].isNotBlank()) {
        append('.')
        append(groups[7])
    }
    val offset = groups[8]
    when {
        offset.isBlank() || offset == "Z" -> append('Z')
        ':' in offset -> append(offset) // ±HH:MM
        else -> {
            append(offset.take(3))
            append(':')
            append(offset.drop(3))
        }
    }
}

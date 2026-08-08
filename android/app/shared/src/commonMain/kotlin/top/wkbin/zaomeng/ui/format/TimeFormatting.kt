package top.wkbin.zaomeng.ui.format

import java.text.SimpleDateFormat
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

private val isoTimestampPattern = Regex(
    """^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?$""",
)

fun String.toLocalDateTimeDisplay(fallback: String = "时间未记录"): String {
    val match = isoTimestampPattern.matchEntire(trim()) ?: return fallback
    val parsed = runCatching {
        val groups = match.groupValues
        val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
            isLenient = false
            clear()
            set(
                groups[1].toInt(),
                groups[2].toInt() - 1,
                groups[3].toInt(),
                groups[4].toInt(),
                groups[5].toInt(),
                groups[6].ifBlank { "0" }.toInt(),
            )
            set(GregorianCalendar.MILLISECOND, groups[7].padEnd(3, '0').take(3).ifBlank { "0" }.toInt())
        }
        calendar.timeInMillis - groups[8].utcOffsetMillis()
    }.getOrNull() ?: return fallback

    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(parsed))
}

private fun String.utcOffsetMillis(): Long {
    if (isBlank() || this == "Z") return 0L
    val sign = if (first() == '-') -1 else 1
    val digits = drop(1).replace(":", "")
    val minutes = digits.take(2).toInt() * 60 + digits.drop(2).take(2).toInt()
    return sign * minutes * 60_000L
}

package top.wkbin.zaomeng.platform

import kotlin.math.absoluteValue
import kotlin.math.roundToLong

/** KMP 安全的一位小数格式化（等价 JVM 的 "%.1f"）。 */
internal fun formatOneDecimal(value: Double): String {
    val scaled = (value * 10).roundToLong()
    return "${scaled / 10}.${(scaled % 10).absoluteValue}"
}

/** KMP 安全的无小数格式化（等价 JVM 的 "%.0f"）。 */
internal fun formatNoDecimal(value: Double): String = value.roundToLong().toString()

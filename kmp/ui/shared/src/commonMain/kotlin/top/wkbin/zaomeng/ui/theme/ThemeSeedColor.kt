package top.wkbin.zaomeng.ui.theme

import androidx.compose.ui.graphics.Color

/** UI 可选主题种子色；持久化只存储其 ARGB 值。 */
enum class ThemeSeedColor(val argb: Long, val displayName: String) {
    DEFAULT(0L, "默认"),
    BLUE(0xFF1A73E8, "蓝"),
    RED(0xFFEA4335, "红"),
    GREEN(0xFF34A853, "绿"),
    PURPLE(0xFF9333EA, "紫"),
    ORANGE(0xFFFB8C00, "橙"),
    TEAL(0xFF009688, "青"),
    PINK(0xFFE91E63, "粉"),
    BROWN(0xFF795548, "棕"),
    ;

    val color: Color
        get() = Color(argb)
}

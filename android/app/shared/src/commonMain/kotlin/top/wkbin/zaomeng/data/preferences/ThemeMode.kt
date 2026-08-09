package top.wkbin.zaomeng.data.preferences

import androidx.compose.ui.graphics.Color

/** 主题模式（跨平台共享）。Monet 系列使用 Material You 动态取色。 */
enum class ThemeMode(val storageValue: String, val isMonet: Boolean) {
    SYSTEM("system", false),
    LIGHT("light", false),
    DARK("dark", false),
    MONET_SYSTEM("monet_system", true),
    MONET_LIGHT("monet_light", true),
    MONET_DARK("monet_dark", true),
    ;

    companion object {
        fun fromStorageValue(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

/** 主题种子色：`DEFAULT`（0）表示跟随系统默认取色，仅 Monet 模式下生效。 */
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

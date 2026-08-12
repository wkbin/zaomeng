package top.wkbin.zaomeng.data.preferences

/** 主题模式（跨平台共享）。动态取色由独立的 dynamicColorEnabled 开关控制。 */
enum class ThemeMode(val storageValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromStorageValue(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

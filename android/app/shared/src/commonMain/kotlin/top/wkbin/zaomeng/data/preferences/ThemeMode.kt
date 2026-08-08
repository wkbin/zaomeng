package top.wkbin.zaomeng.data.preferences

/** 主题模式（shared 副本，旧 :app 的 DataStore 版本退役后统一）。 */
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

package top.wkbin.zaomeng.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import okio.Path
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.NovelConversionForeground
import top.wkbin.zaomeng.platform.ServerPlatform

/** 平台应用依赖：各端入口提供，Koin 模块据此组装。 */
interface AppPlatform {
    val serverPlatform: ServerPlatform

    val dataStore: DataStore<Preferences>

    /** 应用私有文件目录（背景图等用户产生的持久文件；Android filesDir / 桌面用户目录 / iOS Documents）。 */
    val filesDir: Path

    val cacheDir: Path

    val backendPort: Int

    val backendToken: String

    val distillationForeground: DistillationForeground

    val novelConversionForeground: NovelConversionForeground
}

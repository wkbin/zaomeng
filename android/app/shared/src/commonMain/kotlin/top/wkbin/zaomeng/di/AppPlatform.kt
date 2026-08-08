package top.wkbin.zaomeng.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import okio.Path
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.ServerPlatform

/** 平台应用依赖：各端入口提供，Koin 模块据此组装。 */
interface AppPlatform {
    val serverPlatform: ServerPlatform

    val dataStore: DataStore<Preferences>

    val cacheDir: Path

    val backendPort: Int

    val backendToken: String

    val distillationForeground: DistillationForeground
}

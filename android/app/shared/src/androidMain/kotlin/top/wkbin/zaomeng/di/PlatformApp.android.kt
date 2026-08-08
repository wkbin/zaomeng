package top.wkbin.zaomeng.di

import android.content.Context
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.preferences.createDataStore
import top.wkbin.zaomeng.platform.AndroidServerPlatform
import top.wkbin.zaomeng.platform.NoopDistillationForeground
import java.io.File

/** Android 平台依赖：应用私有目录 + 内嵌后端（CIO）。 */
class AndroidAppPlatform(context: Context) : AppPlatform {
    private val appContext = context.applicationContext

    override val serverPlatform = AndroidServerPlatform(appContext)

    override val dataStore = createDataStore(appContext)

    override val cacheDir: Path = File(appContext.cacheDir, "zaomeng-cache").absolutePath.toPath()

    override val backendPort: Int = 8765

    override val backendToken: String = "android-dev-token"

    override val distillationForeground = NoopDistillationForeground
}

package top.wkbin.zaomeng.di

import android.content.Context
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.preferences.createDataStore
import top.wkbin.zaomeng.backend.LocalBackendController
import top.wkbin.zaomeng.backend.LocalBackendEndpointProvider
import top.wkbin.zaomeng.platform.AndroidServerPlatform
import top.wkbin.zaomeng.platform.AndroidNovelConversionForeground
import top.wkbin.zaomeng.platform.AndroidDistillationForeground
import java.io.File

/** Android 平台依赖：应用私有目录 + 内嵌后端（CIO）。 */
class AndroidAppPlatform(context: Context) : AppPlatform {
    private val appContext = context.applicationContext
    private val serverPlatform = AndroidServerPlatform(appContext)
    private val localBackendController = LocalBackendController(
        serverPlatform = serverPlatform,
        port = 0,
        token = BACKEND_TOKEN,
    )

    override val backendController = localBackendController

    override val backendEndpointProvider = LocalBackendEndpointProvider(localBackendController, BACKEND_TOKEN)

    override val secureStore = serverPlatform.secureStore()

    override val dataStore = createDataStore(appContext)

    override val filesDir: Path = File(appContext.filesDir, "zaomeng-files").absolutePath.toPath()

    override val cacheDir: Path = File(appContext.cacheDir, "zaomeng-cache").absolutePath.toPath()

    override val distillationForeground = AndroidDistillationForeground(appContext)

    override val novelConversionForeground = AndroidNovelConversionForeground(appContext)

    private companion object {
        const val BACKEND_TOKEN = "android-dev-token"
    }
}

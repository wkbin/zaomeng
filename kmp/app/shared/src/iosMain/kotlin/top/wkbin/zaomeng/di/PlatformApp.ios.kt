package top.wkbin.zaomeng.di

import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import top.wkbin.zaomeng.data.preferences.createDataStore
import top.wkbin.zaomeng.backend.LocalBackendController
import top.wkbin.zaomeng.backend.LocalBackendEndpointProvider
import top.wkbin.zaomeng.platform.IosServerPlatform
import top.wkbin.zaomeng.platform.NoopNovelConversionForeground
import top.wkbin.zaomeng.platform.NoopDistillationForeground

/** iOS 平台依赖：ApplicationSupport 数据目录 + 内嵌后端（CIO）。 */
object IosAppPlatform : AppPlatform {
    private val serverPlatform = IosServerPlatform()
    private val localBackendController = LocalBackendController(
        serverPlatform = serverPlatform,
        port = 0,
        token = BACKEND_TOKEN,
    )

    override val backendController = localBackendController

    override val backendEndpointProvider = LocalBackendEndpointProvider(localBackendController, BACKEND_TOKEN)

    override val secureStore = serverPlatform.secureStore()

    override val dataStore = createDataStore()

    override val filesDir: Path = documentsDirectory().toPath() / "zaomeng-files"

    override val cacheDir: Path = cachesDirectory().toPath() / "zaomeng-cache"

    override val distillationForeground = NoopDistillationForeground

    override val novelConversionForeground = NoopNovelConversionForeground

    private const val BACKEND_TOKEN = "ios-dev-token"
}

private fun cachesDirectory(): String {
    val url = NSFileManager.defaultManager.URLForDirectory(
        directory = NSCachesDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    return requireNotNull(url?.path) { "NSCachesDirectory unavailable" }
}

private fun documentsDirectory(): String {
    val url = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    return requireNotNull(url?.path) { "NSDocumentDirectory unavailable" }
}

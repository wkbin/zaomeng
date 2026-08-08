package top.wkbin.zaomeng.di

import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import top.wkbin.zaomeng.data.preferences.createDataStore
import top.wkbin.zaomeng.platform.IosServerPlatform
import top.wkbin.zaomeng.platform.NoopDistillationForeground

/** iOS 平台依赖：ApplicationSupport 数据目录 + 内嵌后端（CIO）。 */
object IosAppPlatform : AppPlatform {
    override val serverPlatform = IosServerPlatform()

    override val dataStore = createDataStore()

    override val cacheDir: Path = cachesDirectory().toPath() / "zaomeng-cache"

    override val backendPort: Int = 8765

    override val backendToken: String = "ios-dev-token"

    override val distillationForeground = NoopDistillationForeground
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

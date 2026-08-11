package top.wkbin.zaomeng.di

import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.preferences.createDataStore
import top.wkbin.zaomeng.backend.LocalBackendController
import top.wkbin.zaomeng.backend.LocalBackendEndpointProvider
import top.wkbin.zaomeng.platform.JvmServerPlatform
import top.wkbin.zaomeng.platform.NoopNovelConversionForeground
import top.wkbin.zaomeng.platform.NoopDistillationForeground

/** 桌面平台依赖：仓库数据目录 + 环境变量端口/token。 */
class DesktopAppPlatform : AppPlatform {
    private val serverPlatform = JvmServerPlatform()
    private val backendPort = System.getenv("ZAOMENG_PORT")?.toIntOrNull() ?: 0
    private val backendToken = System.getenv("ZAOMENG_TOKEN") ?: "desktop-dev-token"
    private val localBackendController = LocalBackendController(serverPlatform, backendPort, backendToken)

    override val backendController = localBackendController

    override val backendEndpointProvider = LocalBackendEndpointProvider(localBackendController, backendToken)

    override val secureStore = serverPlatform.secureStore()

    override val dataStore = createDataStore()

    override val filesDir: Path =
        (System.getProperty("user.home") ?: ".").toPath() / ".zaomeng" / "files"

    override val cacheDir: Path =
        (System.getProperty("java.io.tmpdir") ?: ".").toPath() / "zaomeng-cache"

    override val distillationForeground = NoopDistillationForeground

    override val novelConversionForeground = NoopNovelConversionForeground
}

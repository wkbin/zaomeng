package top.wkbin.zaomeng.di

import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.preferences.createDataStore
import top.wkbin.zaomeng.platform.JvmServerPlatform
import top.wkbin.zaomeng.platform.NoopNovelConversionForeground
import top.wkbin.zaomeng.platform.NoopDistillationForeground

/** 桌面平台依赖：仓库数据目录 + 环境变量端口/token。 */
class DesktopAppPlatform : AppPlatform {
    override val serverPlatform = JvmServerPlatform()

    override val dataStore = createDataStore()

    override val cacheDir: Path =
        (System.getProperty("java.io.tmpdir") ?: ".").toPath() / "zaomeng-cache"

    override val backendPort: Int = System.getenv("ZAOMENG_PORT")?.toIntOrNull() ?: 8765

    override val backendToken: String = System.getenv("ZAOMENG_TOKEN") ?: "desktop-dev-token"

    override val distillationForeground = NoopDistillationForeground

    override val novelConversionForeground = NoopNovelConversionForeground
}

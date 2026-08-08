package top.wkbin.zaomeng.app.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.vinceglb.filekit.FileKit
import org.koin.core.context.startKoin
import top.wkbin.zaomeng.app.shared.App
import top.wkbin.zaomeng.di.DesktopAppPlatform
import top.wkbin.zaomeng.di.sharedAppModule

/** 桌面入口壳：启动 Koin（内嵌后端由 LocalBackendController 在书卷架加载时拉起）。 */
fun main() {
    FileKit.init(appId = "top.wkbin.zaomeng")
    application {
        startKoin { modules(sharedAppModule(DesktopAppPlatform())) }

        Window(
            onCloseRequest = ::exitApplication,
            title = "Zaomeng",
        ) {
            App()
        }
    }
}

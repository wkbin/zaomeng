package top.wkbin.zaomeng.app.shared

import androidx.compose.ui.window.ComposeUIViewController
import org.koin.core.context.startKoin
import platform.UIKit.UIViewController
import top.wkbin.zaomeng.di.IosAppPlatform
import top.wkbin.zaomeng.di.sharedAppModule

/** iOS 入口：启动 Koin（内嵌后端由 LocalBackendController 在书卷架加载时拉起）。 */
fun MainViewController(): UIViewController {
    startKoin { modules(sharedAppModule(IosAppPlatform)) }
    return ComposeUIViewController {
        App()
    }
}

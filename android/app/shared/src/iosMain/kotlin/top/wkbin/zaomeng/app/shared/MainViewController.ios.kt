package top.wkbin.zaomeng.app.shared

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** iOS 入口：Compose 控制器挂共享 UI，并在启动时拉起内嵌 Ktor 后端。 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    LaunchedEffect(Unit) {
        IosBackend.start()
    }
    App()
}

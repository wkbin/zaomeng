package top.wkbin.zaomeng.app.shared

import androidx.compose.runtime.Composable
import top.wkbin.zaomeng.navigation.ZaomengNavHost
import top.wkbin.zaomeng.ui.theme.MyApplicationTheme

/** 跨平台共享 UI 入口：androidApp / desktopApp / iosApp 都渲染这个组合。 */
@Composable
fun App() {
    MyApplicationTheme {
        ZaomengNavHost()
    }
}

package top.wkbin.zaomeng.app.shared

import androidx.compose.runtime.Composable
import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.zaomeng.feature.bookshelf.BookshelfScreen
import top.wkbin.zaomeng.feature.bookshelf.BookshelfViewModel
import top.wkbin.zaomeng.ui.theme.MyApplicationTheme

/** 跨平台共享 UI 入口：androidApp / desktopApp / iosApp 都渲染这个组合。 */
@Composable
fun App() {
    MyApplicationTheme {
        val viewModel: BookshelfViewModel = koinViewModel()
        BookshelfScreen(
            viewModel = viewModel,
            onImport = {},
            onOpenSettings = {},
            onOpenCards = {},
            onOpenSessions = {},
            onOpenCrossover = {},
            onOpenRun = {},
        )
    }
}

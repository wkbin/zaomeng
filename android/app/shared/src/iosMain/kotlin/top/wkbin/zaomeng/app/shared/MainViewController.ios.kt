package top.wkbin.zaomeng.app.shared

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.GlobalContext
import platform.UIKit.UIViewController
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.di.IosAppPlatform
import top.wkbin.zaomeng.di.sharedAppModule

/** iOS 入口：启动 Koin（内嵌后端由 LocalBackendController 在书卷架加载时拉起）。 */
fun MainViewController(): UIViewController {
    startKoin { modules(sharedAppModule(IosAppPlatform)) }
    // UI 组合前同步读取持久化主题，避免启动首帧闪默认主题（参考 KernelSU）。
    val initialPreferences = runBlocking {
        GlobalContext.get().get<AppPreferencesRepository>().currentPreferences()
    }
    return ComposeUIViewController {
        App(
            initialThemeMode = initialPreferences.themeMode,
            initialThemeSeedColorArgb = initialPreferences.themeSeedColorArgb,
            initialUiScale = initialPreferences.uiScale,
        )
    }
}

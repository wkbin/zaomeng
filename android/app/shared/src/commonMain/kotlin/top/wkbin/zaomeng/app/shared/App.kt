package top.wkbin.zaomeng.app.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.ThemeMode
import top.wkbin.zaomeng.data.update.AppUpdateUiState
import top.wkbin.zaomeng.navigation.ZaomengNavHost
import top.wkbin.zaomeng.ui.theme.MyApplicationTheme

/**
 * 跨平台共享 UI 入口：androidApp / desktopApp / iosApp 都渲染这个组合。
 * 更新检查/下载与深链属于平台能力，通过可选参数由平台入口注入（桌面/iOS 使用默认空实现）。
 */
@Composable
fun App(
    appUpdateState: AppUpdateUiState = AppUpdateUiState(),
    onCheckForAppUpdate: (Boolean) -> Unit = {},
    onDownloadAppUpdate: () -> Unit = {},
    startupUpdateCheckDisabled: Boolean = false,
    onStartupUpdateCheckDisabledChange: (Boolean) -> Unit = {},
    launchChaptersRunId: String? = null,
    onChaptersLaunchConsumed: () -> Unit = {},
    /** 主题深浅变化回调（桌面端用于原生标题栏适配）。 */
    onThemeChanged: (Boolean) -> Unit = {},
) {
    val preferencesRepository: AppPreferencesRepository = koinInject()
    val themeMode by preferencesRepository.themeMode.collectAsStateWithLifecycle(
        initialValue = ThemeMode.SYSTEM,
    )
    val themeSeedColorArgb by preferencesRepository.themeSeedColorArgb.collectAsStateWithLifecycle(
        initialValue = 0L,
    )
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM, ThemeMode.MONET_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT, ThemeMode.MONET_LIGHT -> false
        ThemeMode.DARK, ThemeMode.MONET_DARK -> true
    }
    SideEffect { onThemeChanged(darkTheme) }
    MyApplicationTheme(
        themeMode = themeMode,
        themeSeedColorArgb = themeSeedColorArgb,
    ) {
        ZaomengNavHost(
            appUpdateState = appUpdateState,
            onCheckForAppUpdate = onCheckForAppUpdate,
            onDownloadAppUpdate = onDownloadAppUpdate,
            startupUpdateCheckDisabled = startupUpdateCheckDisabled,
            onStartupUpdateCheckDisabledChange = onStartupUpdateCheckDisabledChange,
            launchChaptersRunId = launchChaptersRunId,
            onChaptersLaunchConsumed = onChaptersLaunchConsumed,
        )
    }
}

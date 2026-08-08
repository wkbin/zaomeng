package top.wkbin.zaomeng.app.shared

import androidx.compose.runtime.Composable
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
) {
    val preferencesRepository: AppPreferencesRepository = koinInject()
    val themeMode by preferencesRepository.themeMode.collectAsStateWithLifecycle(
        initialValue = ThemeMode.SYSTEM,
    )
    MyApplicationTheme(themeMode = themeMode, dynamicColor = true) {
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

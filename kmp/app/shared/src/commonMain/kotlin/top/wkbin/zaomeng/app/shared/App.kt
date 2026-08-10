package top.wkbin.zaomeng.app.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.UI_SCALE_DEFAULT
import top.wkbin.zaomeng.data.preferences.ThemeMode
import top.wkbin.zaomeng.data.update.AppUpdateUiState
import top.wkbin.zaomeng.navigation.ZaomengNavHost
import top.wkbin.zaomeng.ui.theme.ZaomengTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

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
    /** 平台入口在启动时同步读取的持久化主题，避免首帧闪主题；未提供时用默认值。 */
    initialThemeMode: ThemeMode = ThemeMode.SYSTEM,
    initialThemeSeedColorArgb: Long = 0L,
    /** 启动时同步读取的持久化动态取色开关，避免首帧先按静态主题渲染。 */
    initialDynamicColorEnabled: Boolean = false,
    /** 平台入口在启动时同步读取的持久化界面缩放，避免首帧先按 100% 渲染。 */
    initialUiScale: Float = UI_SCALE_DEFAULT,
    /** Android 内置导航返回处理开关切换回调，由平台入口持久化。 */
    onBuiltInBackHandlingEnabledChange: (Boolean) -> Unit = {},
    /** 平台入口在启动时同步读取的内置导航返回处理开关。 */
    initialBuiltInBackHandlingEnabled: Boolean = true,
) {
    val preferencesRepository: AppPreferencesRepository = koinInject()
    val themeMode by preferencesRepository.themeMode.collectAsStateWithLifecycle(
        initialValue = initialThemeMode,
    )
    val themeSeedColorArgb by preferencesRepository.themeSeedColorArgb.collectAsStateWithLifecycle(
        initialValue = initialThemeSeedColorArgb,
    )
    val dynamicColorEnabled by preferencesRepository.dynamicColorEnabled.collectAsStateWithLifecycle(
        initialValue = initialDynamicColorEnabled,
    )
    val uiScale by preferencesRepository.uiScale.collectAsStateWithLifecycle(
        initialValue = initialUiScale,
    )
    val builtInBackHandlingEnabled by preferencesRepository.builtInBackHandlingEnabled.collectAsStateWithLifecycle(
        initialValue = initialBuiltInBackHandlingEnabled,
    )
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    SideEffect { onThemeChanged(darkTheme) }
    // 全局界面缩放（参考 KernelSU pageScale）：覆盖 Density，仅缩放本应用 UI，不影响系统设置。
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, uiScale) {
        Density(
            density = baseDensity.density * uiScale,
            fontScale = baseDensity.fontScale * uiScale,
        )
    }
    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        ZaomengTheme(
            themeMode = themeMode,
            dynamicColorEnabled = dynamicColorEnabled,
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
                onBuiltInBackHandlingEnabledChange = onBuiltInBackHandlingEnabledChange,
                builtInBackHandlingEnabled = builtInBackHandlingEnabled,
            )
        }
    }
}

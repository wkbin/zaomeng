package top.wkbin.zaomeng.app.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import io.github.vinceglb.filekit.FileKit
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.GlobalContext
import org.jetbrains.compose.resources.painterResource
import top.wkbin.zaomeng.app.shared.App
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.update.AppUpdateUiState
import top.wkbin.zaomeng.data.update.ReleaseUpdateChecker
import top.wkbin.zaomeng.di.DesktopAppPlatform
import top.wkbin.zaomeng.di.sharedAppModule
import top.wkbin.zaomeng.feature.update.AppUpdateDialog
import top.wkbin.zaomeng.platform.createHttpClientEngine
import top.wkbin.zaomeng.platform.rememberOpenExternalUrl
import zaomeng.app.shared.generated.resources.Res
import zaomeng.app.shared.generated.resources.zaomeng_logo
import java.util.prefs.Preferences

/** 桌面入口壳：启动 Koin（内嵌后端由 LocalBackendController 在书卷架加载时拉起）。 */
fun main() {
    FileKit.init(appId = "top.wkbin.zaomeng")
    application {
        startKoin { modules(sharedAppModule(DesktopAppPlatform())) }
        // 窗口创建前同步读取持久化主题，避免启动首帧闪默认主题（参考 KernelSU）。
        val initialPreferences = runBlocking {
            GlobalContext.get().get<AppPreferencesRepository>().currentPreferences()
        }

        Window(
            onCloseRequest = ::exitApplication,
            title = "造梦",
            icon = painterResource(Res.drawable.zaomeng_logo),
            state = rememberWindowState(size = DpSize(1200.dp, 800.dp)),
        ) {
            val scope = rememberCoroutineScope()
            val openUrl = rememberOpenExternalUrl()
            val updateClient = remember { HttpClient(createHttpClientEngine()) }
            var appUpdateState by remember { mutableStateOf(AppUpdateUiState()) }
            var dismissedUpdateVersion by remember { mutableStateOf<String?>(null) }
            var startupUpdateCheckDisabled by remember {
                mutableStateOf(DesktopUpdatePreferences.startupCheckDisabled)
            }

            fun checkForAppUpdate(manual: Boolean) {
                if (appUpdateState.checking) return
                if (manual) dismissedUpdateVersion = null
                appUpdateState = appUpdateState.copy(
                    checking = true,
                    error = "",
                    message = if (manual) "正在检查 GitHub Release" else appUpdateState.message,
                )
                scope.launch {
                    try {
                        val update = ReleaseUpdateChecker(updateClient).checkForUpdate()
                        appUpdateState = AppUpdateUiState(
                            availableUpdate = update,
                            message = if (update == null) "当前已是最新版本。" else "发现 ${update.version} 新版本。",
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        appUpdateState = appUpdateState.copy(
                            checking = false,
                            error = if (manual) error.message ?: "检查更新失败。" else "",
                            message = if (manual) "" else appUpdateState.message,
                        )
                    }
                }
            }

            LaunchedEffect(Unit) {
                if (!startupUpdateCheckDisabled) checkForAppUpdate(manual = false)
            }

            App(
                appUpdateState = appUpdateState,
                onCheckForAppUpdate = ::checkForAppUpdate,
                onDownloadAppUpdate = { appUpdateState.availableUpdate?.let { openUrl(it.downloadUrl) } },
                startupUpdateCheckDisabled = startupUpdateCheckDisabled,
                onStartupUpdateCheckDisabledChange = { disabled ->
                    startupUpdateCheckDisabled = disabled
                    DesktopUpdatePreferences.startupCheckDisabled = disabled
                },
                initialThemeMode = initialPreferences.themeMode,
                initialThemeSeedColorArgb = initialPreferences.themeSeedColorArgb,
                initialDynamicColorEnabled = initialPreferences.dynamicColorEnabled,
                initialUiScale = initialPreferences.uiScale,
                onThemeChanged = { dark -> applyNativeTitleBarTheme(window, dark) },
            )

            appUpdateState.availableUpdate?.let { update ->
                if (dismissedUpdateVersion != update.version) {
                    AppUpdateDialog(
                        update = update,
                        downloadState = appUpdateState.downloadState,
                        onDismiss = { dismissedUpdateVersion = update.version },
                        onDownload = { openUrl(update.downloadUrl) },
                        downloadLabel = "去下载",
                    )
                }
            }
        }
    }
}

/** 桌面端更新偏好（java.util.prefs）。 */
private object DesktopUpdatePreferences {
    private val prefs = Preferences.userRoot().node("zaomeng/desktop")

    var startupCheckDisabled: Boolean
        get() = prefs.getBoolean("startupUpdateCheckDisabled", false)
        set(value) = prefs.putBoolean("startupUpdateCheckDisabled", value)
}

/**
 * 让原生窗口标题栏跟随应用深浅主题。
 *
 * Windows：DwmSetWindowAttribute(DWMWA_USE_IMMERSIVE_DARK_MODE)。
 * macOS/Linux 无 per-window 深色标题栏 API，保持系统外观。
 */
private fun applyNativeTitleBarTheme(window: java.awt.Window, dark: Boolean) {
    val os = System.getProperty("os.name").lowercase()
    if (!os.contains("win")) return
    if (window !is java.awt.Frame) return
    runCatching {
        val title = window.title ?: return
        val hwnd = User32.INSTANCE.FindWindow(null, title)
        if (hwnd == null || hwnd.pointer == Pointer.NULL) return
        val value = IntByReference(if (dark) 1 else 0)
        // DWMWA_USE_IMMERSIVE_DARK_MODE = 20（Win10 1903+）；旧版为 19，失败时回退
        val result = NativeDwmapi.INSTANCE.DwmSetWindowAttribute(
            hwnd,
            20,
            value,
            Int.SIZE_BYTES,
        )
        if (result != 0) {
            NativeDwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, 19, value, Int.SIZE_BYTES)
        }
    }
}

private interface NativeDwmapi : Library {
    fun DwmSetWindowAttribute(
        hwnd: WinDef.HWND,
        attribute: Int,
        value: IntByReference,
        size: Int,
    ): Int

    companion object {
        val INSTANCE: NativeDwmapi = Native.load("dwmapi", NativeDwmapi::class.java)
    }
}

package top.wkbin.zaomeng.app.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import org.koin.core.context.startKoin
import org.jetbrains.compose.resources.painterResource
import top.wkbin.zaomeng.app.shared.App
import top.wkbin.zaomeng.di.DesktopAppPlatform
import top.wkbin.zaomeng.di.sharedAppModule
import zaomeng.app.shared.generated.resources.Res
import zaomeng.app.shared.generated.resources.zaomeng_logo

/** 桌面入口壳：启动 Koin（内嵌后端由 LocalBackendController 在书卷架加载时拉起）。 */
fun main() {
    FileKit.init(appId = "top.wkbin.zaomeng")
    application {
        startKoin { modules(sharedAppModule(DesktopAppPlatform())) }

        Window(
            onCloseRequest = ::exitApplication,
            title = "造梦",
            icon = painterResource(Res.drawable.zaomeng_logo),
            state = rememberWindowState(size = DpSize(1200.dp, 800.dp)),
        ) {
            App(
                onThemeChanged = { dark -> applyNativeTitleBarTheme(window, dark) },
            )
        }
    }
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

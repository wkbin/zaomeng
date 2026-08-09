package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import java.awt.Color
import java.awt.Image
import java.awt.SystemTray
import java.awt.Desktop
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.net.URI

/** 复用的托盘图标（惰性创建，避免每次 Toast 都新增/移除 TrayIcon 造成闪烁）。 */
private var desktopTrayIcon: TrayIcon? = null

@Composable
actual fun rememberOpenExternalUrl(): (String) -> Unit = { url ->
    runCatching {
        if (url.startsWith("mailto:")) {
            Desktop.getDesktop().mail(URI(url))
        } else {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

/** 桌面提示：优先系统托盘通知；托盘不可用时退回控制台日志。 */
@Composable
actual fun rememberToast(): (String) -> Unit = { message ->
    runCatching {
        if (!SystemTray.isSupported()) {
            println("[Toast] $message")
            return@runCatching
        }
        val tray = SystemTray.getSystemTray()
        val icon = desktopTrayIcon ?: TrayIcon(createToastIcon(), "造梦").also {
            desktopTrayIcon = it
            tray.add(it)
        }
        icon.displayMessage("造梦", message, TrayIcon.MessageType.INFO)
    }
}

private fun createToastIcon(): Image {
    val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        graphics.color = Color(0x5B, 0x4B, 0xE6)
        graphics.fillOval(1, 1, 14, 14)
        graphics.color = Color.WHITE
        graphics.fillOval(5, 5, 6, 6)
    } finally {
        graphics.dispose()
    }
    return image
}

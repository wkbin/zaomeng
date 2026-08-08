package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import java.awt.Desktop
import java.net.URI

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

@Composable
actual fun rememberToast(): (String) -> Unit = { message -> println("[Toast] $message") }

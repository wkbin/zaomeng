package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import platform.UIKit.UIPasteboard

@Composable
actual fun rememberClipboardTextWriter(): suspend (String) -> Unit = { text ->
    UIPasteboard.generalPasteboard.string = text
}

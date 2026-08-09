package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
actual fun rememberClipboardTextWriter(): suspend (String) -> Unit = { text ->
    Toolkit.getDefaultToolkit().systemClipboard
        .setContents(StringSelection(text), null)
}

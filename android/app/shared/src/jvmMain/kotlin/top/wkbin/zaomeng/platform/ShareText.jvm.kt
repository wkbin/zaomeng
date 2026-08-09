package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** 桌面端"分享"语义：把文本复制到系统剪贴板；失败时退回控制台日志。 */
@Composable
actual fun rememberShareText(): (text: String) -> Unit = { text ->
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }.onFailure { println("[Share] $text") }
}

package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

/** 复制纯文本到系统剪贴板（挂起，调用方在协程中使用）。 */
@Composable
expect fun rememberClipboardTextWriter(): suspend (String) -> Unit

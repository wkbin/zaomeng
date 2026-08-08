package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberShareText(): (text: String) -> Unit = { text -> println("[Share] $text") }

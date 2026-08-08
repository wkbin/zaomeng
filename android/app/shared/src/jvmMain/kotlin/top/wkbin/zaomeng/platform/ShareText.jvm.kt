package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberShareText(): (String) -> Unit = { text -> println("[Share] $text") }

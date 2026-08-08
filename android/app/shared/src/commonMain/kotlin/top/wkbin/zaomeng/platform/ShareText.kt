package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

/** 调起系统分享（Android ACTION_SEND；桌面/iOS TODO）。 */
@Composable
expect fun rememberShareText(): (text: String) -> Unit

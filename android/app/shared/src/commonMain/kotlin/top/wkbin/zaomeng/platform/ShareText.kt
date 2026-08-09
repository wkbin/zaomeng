package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

/** 调起系统分享（Android ACTION_SEND；桌面复制剪贴板；iOS 分享面板）。 */
@Composable
expect fun rememberShareText(): (text: String) -> Unit

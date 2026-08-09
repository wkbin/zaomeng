package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

/**
 * 请求通知权限（Android POST_NOTIFICATIONS；桌面/iOS 视为已授权）。
 * 返回一个触发请求的函数。
 */
@Composable
expect fun rememberNotificationPermissionRequester(onResult: (Boolean) -> Unit): () -> Unit

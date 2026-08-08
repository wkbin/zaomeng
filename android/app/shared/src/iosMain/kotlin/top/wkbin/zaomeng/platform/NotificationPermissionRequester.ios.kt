package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberNotificationPermissionRequester(onResult: (Boolean) -> Unit): () -> Unit =
    { onResult(true) }

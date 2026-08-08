package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberOpenExternalUrl(): (String) -> Unit = {
    // TODO: UIApplication.openURL（iOS 阶段补充）
}

@Composable
actual fun rememberToast(): (String) -> Unit = { message -> println("[Toast] $message") }

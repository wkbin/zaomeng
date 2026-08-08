package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberShareText(): (String) -> Unit = {
    // TODO: UIActivityViewController 分享（iOS 阶段补充）
}

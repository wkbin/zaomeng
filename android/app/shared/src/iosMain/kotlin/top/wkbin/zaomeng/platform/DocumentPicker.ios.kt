package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberDocumentPicker(onPicked: (name: String, bytes: ByteArray) -> Unit): () -> Unit = {
    // TODO: UIDocumentPicker（iOS 阶段补充）
}

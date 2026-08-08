package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberZipFilePicker(onPicked: (name: String, bytes: ByteArray) -> Unit): () -> Unit = {
    // TODO: UIDocumentPicker 插件包选择（iOS 阶段补充）
}

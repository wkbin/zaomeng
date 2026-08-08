package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberImagePicker(onPicked: (uri: String) -> Unit): () -> Unit = {
    // TODO: UIDocumentPicker 图片选择（iOS 阶段补充）
}

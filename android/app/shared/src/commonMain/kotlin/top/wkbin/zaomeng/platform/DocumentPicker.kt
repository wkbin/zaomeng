package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

/** 选择任意文档文件，回调文件名与字节内容。 */
@Composable
expect fun rememberDocumentPicker(onPicked: (name: String, bytes: ByteArray) -> Unit): () -> Unit

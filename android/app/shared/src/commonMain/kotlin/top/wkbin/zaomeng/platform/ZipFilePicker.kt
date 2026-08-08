package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

/** 选择 ZIP 插件包文件，回调文件名与字节内容。 */
@Composable
expect fun rememberZipFilePicker(onPicked: (name: String, bytes: ByteArray) -> Unit): () -> Unit

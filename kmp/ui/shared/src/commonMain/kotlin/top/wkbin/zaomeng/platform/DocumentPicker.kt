package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch

/** 选择任意文档文件，回调文件名与字节内容（FileKit 三端原生对话框）。 */
@Composable
fun rememberDocumentPicker(onPicked: (name: String, bytes: ByteArray) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val launcher = rememberFilePickerLauncher(type = FileKitType.File()) { file ->
        if (file != null) {
            scope.launch {
                runCatching { file.name to file.readBytes() }
                    .onSuccess { (name, bytes) -> onPicked(name, bytes) }
            }
        }
    }
    return { launcher.launch() }
}

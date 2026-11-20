package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch

/**
 * 选择任意文档文件，回调文件名与字节内容（FileKit 三端原生对话框）。
 *
 * @param onPicked 读取成功回调
 * @param onCancelled 用户取消选择或读取失败时回调，调用方据此复位"正在读取"状态；
 *   不提供该回调时取消与读取失败都不会有任何通知，界面会一直卡在读取中。
 */
@Composable
fun rememberDocumentPicker(
    onCancelled: () -> Unit = {},
    onPicked: (name: String, bytes: ByteArray) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val launcher = rememberFilePickerLauncher(type = FileKitType.File()) { file ->
        if (file == null) {
            onCancelled()
        } else {
            scope.launch {
                runCatching { file.name to file.readBytes() }
                    .onSuccess { (name, bytes) -> onPicked(name, bytes) }
                    .onFailure { onCancelled() }
            }
        }
    }
    return { launcher.launch() }
}
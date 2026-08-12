package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import io.github.vinceglb.filekit.extension
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path

/**
 * 选择一张本地图片并拷贝到应用文件目录，回调其本地文件路径。
 * 三端均使用 FileKit 原生相册/文件选择，避免持久化 content:// 授权问题。
 */
@Composable
fun rememberImagePicker(filesDir: Path, onPicked: (uri: String) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val launcher = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        if (file != null) {
            scope.launch {
                runCatching {
                    val extension = file.extension.ifBlank { "jpg" }
                    val destination = filesDir / "chat-background.$extension"
                    FileSystem.SYSTEM.createDirectories(filesDir)
                    FileSystem.SYSTEM.write(destination) { write(file.readBytes()) }
                    destination.toString()
                }.onSuccess(onPicked)
            }
        }
    }
    return { launcher.launch() }
}

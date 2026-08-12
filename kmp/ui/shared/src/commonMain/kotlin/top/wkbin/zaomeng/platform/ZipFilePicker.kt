package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import okio.Path

/** 选择 ZIP 插件包文件，回调文件名与字节内容（FileKit 三端原生对话框，限制 10 MB）。 */
@Composable
fun rememberZipFilePicker(filesDir: Path, onPicked: (name: String, bytes: ByteArray) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val launcher = rememberFilePickerLauncher(type = FileKitType.File("zip")) { file ->
        if (file != null) {
            scope.launch {
                runCatching {
                    val bytes = file.readBytes()
                    require(bytes.size <= MaxPluginPackageBytes) { "插件内容超过 10 MB。" }
                    file.name to bytes
                }.onSuccess { (name, bytes) -> onPicked(name, bytes) }
            }
        }
    }
    return { launcher.launch() }
}

private const val MaxPluginPackageBytes = 10 * 1024 * 1024

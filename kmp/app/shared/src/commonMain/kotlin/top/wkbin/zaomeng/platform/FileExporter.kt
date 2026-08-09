package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import io.github.vinceglb.filekit.sink
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import kotlinx.coroutines.launch
import kotlinx.io.okio.asOkioSink
import okio.Sink
import okio.buffer
import okio.use

/**
 * 导出文件到用户选择的位置：返回一个触发保存对话框的函数，
 * 用户确认后调用 [onSave]（写入 okio.Sink），取消则调用 [onCancelled]。
 * 三端均使用 FileKit 原生保存对话框。
 */
@Composable
fun rememberFileExporter(
    onSave: suspend (Sink) -> Unit,
    onCancelled: () -> Unit,
): (suggestedName: String, mimeType: String) -> Unit {
    val scope = rememberCoroutineScope()
    val launcher = rememberFileSaverLauncher(
        dialogSettings = FileKitDialogSettings.createDefault(),
    ) { file ->
        if (file == null) {
            onCancelled()
        } else {
            scope.launch {
                val saved = runCatching {
                    file.sink().asOkioSink().buffer().use { onSave(it) }
                    true
                }.getOrDefault(false)
                if (!saved) onCancelled()
            }
        }
    }
    return { suggestedName, mimeType ->
        val extension = extensionOf(mimeType)
        val baseName = suggestedName
            .removeSuffix(".$extension")
            .takeIf { it.isNotBlank() }
            ?: "export"
        launcher.launch(
            suggestedName = baseName,
            defaultExtension = extension,
            allowedExtensions = setOf(extension),
        )
    }
}

private fun extensionOf(mimeType: String): String = when (mimeType.lowercase()) {
    "application/zip" -> "zip"
    "application/json" -> "json"
    "text/markdown" -> "md"
    "text/plain" -> "txt"
    else -> mimeType.substringAfter('/', "").ifBlank { "txt" }
}

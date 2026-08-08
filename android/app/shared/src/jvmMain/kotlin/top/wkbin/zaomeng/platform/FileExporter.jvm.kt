package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Sink
import okio.buffer
import okio.sink
import okio.use
import java.io.File
import javax.swing.JFileChooser

@Composable
actual fun rememberFileExporter(
    onSave: suspend (Sink) -> Unit,
    onCancelled: () -> Unit,
): (suggestedName: String, mimeType: String) -> Unit {
    val scope = rememberCoroutineScope()
    return remember {
        { suggestedName, _ ->
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val chooser = JFileChooser().apply { selectedFile = File(suggestedName) }
                        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
                            return@runCatching false
                        }
                        val output = chooser.selectedFile.outputStream()
                        output.sink().buffer().use { onSave(it) }
                        true
                    }.getOrDefault(false)
                }
                if (!saved) onCancelled()
            }
        }
    }
}

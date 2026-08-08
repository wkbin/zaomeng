package top.wkbin.zaomeng.platform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Sink
import okio.buffer
import okio.sink
import okio.use

@Composable
actual fun rememberFileExporter(
    onSave: suspend (Sink) -> Unit,
    onCancelled: () -> Unit,
): (suggestedName: String, mimeType: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) {
            onCancelled()
        } else {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: return@runCatching false
                        output.sink().buffer().use { onSave(it) }
                        true
                    }.getOrDefault(false)
                }
                if (!saved) onCancelled()
            }
        }
    }
    return remember { { suggestedName, _ -> launcher.launch(suggestedName) } }
}

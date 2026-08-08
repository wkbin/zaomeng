package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import okio.Sink

@Composable
actual fun rememberFileExporter(
    onSave: suspend (Sink) -> Unit,
    onCancelled: () -> Unit,
): (suggestedName: String, mimeType: String) -> Unit = { _, _ -> onCancelled() }

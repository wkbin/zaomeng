package top.wkbin.zaomeng.platform

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

@Composable
actual fun rememberZipFilePicker(onPicked: (name: String, bytes: ByteArray) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = context.contentResolver.displayName(uri) ?: "plugin.zip"
                    val bytes = context.contentResolver.openInputStream(uri)
                        ?.use { it.readLimitedBytes(MaxPluginPackageBytes) }
                        ?: error("无法读取所选插件包。")
                    name to bytes
                }
            }.onSuccess { (name, bytes) -> onPicked(name, bytes) }
        }
    }
    return remember {
        { launcher.launch(arrayOf("application/zip", "application/octet-stream")) }
    }
}

private const val MaxPluginPackageBytes = 10 * 1024 * 1024

private fun ContentResolver.displayName(uri: Uri): String? =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

private fun InputStream.readLimitedBytes(limit: Int): ByteArray {
    require(limit >= 0) { "插件内容超过 10 MB。" }
    val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (output.size() + count > limit) error("插件内容超过 10 MB。")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

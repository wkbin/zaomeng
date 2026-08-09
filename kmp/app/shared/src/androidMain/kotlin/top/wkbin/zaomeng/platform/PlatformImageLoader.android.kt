package top.wkbin.zaomeng.platform

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.File
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap

@Composable
actual fun rememberPlatformImage(uri: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(uri) {
        runCatching {
            val bytes = if (uri.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                    input.readBytes()
                }
            } else {
                File(uri).takeIf { it.isFile }?.readBytes()
            } ?: return@remember null
            decodeImageBitmap(bytes)
        }.getOrNull()
    }
}

package top.wkbin.zaomeng.platform

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap

@Composable
actual fun rememberPlatformImage(uri: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(uri) {
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                decodeImageBitmap(input.readBytes())
            }
        }.getOrNull()
    }
}

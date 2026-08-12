package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap
import java.io.File

@Composable
actual fun rememberPlatformImage(uri: String): ImageBitmap? = remember(uri) {
    runCatching {
        val file = File(uri)
        if (file.isFile) decodeImageBitmap(file.readBytes()) else null
    }.getOrNull()
}

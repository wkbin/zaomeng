package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.ImageBitmap
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap
import java.io.File

@Composable
actual fun rememberPlatformImage(uri: String): ImageBitmap? = key(uri) {
    produceState<ImageBitmap?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(uri)
                if (file.isFile) decodeImageBitmap(file.readBytes()) else null
            }.getOrNull()
        }
    }.value
}

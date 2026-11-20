package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.ImageBitmap
import okio.FileSystem
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap

@Composable
actual fun rememberPlatformImage(uri: String): ImageBitmap? = key(uri) {
    produceState<ImageBitmap?>(initialValue = null) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val path = uri.toPath()
                if (FileSystem.SYSTEM.exists(path)) {
                    FileSystem.SYSTEM.read(path) { decodeImageBitmap(readByteArray()) }
                } else {
                    null
                }
            }.getOrNull()
        }
    }.value
}

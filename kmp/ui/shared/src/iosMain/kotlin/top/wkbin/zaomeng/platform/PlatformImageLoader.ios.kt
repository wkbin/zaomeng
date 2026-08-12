package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import okio.FileSystem
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap

@Composable
actual fun rememberPlatformImage(uri: String): ImageBitmap? = remember(uri) {
    runCatching {
        val path = uri.toPath()
        if (FileSystem.SYSTEM.exists(path)) {
            FileSystem.SYSTEM.read(path) { source -> decodeImageBitmap(source.readByteArray()) }
        } else {
            null
        }
    }.getOrNull()
}

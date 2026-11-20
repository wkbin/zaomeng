package top.wkbin.zaomeng.platform

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap

@Composable
actual fun rememberPlatformImage(uri: String): ImageBitmap? {
    val context = LocalContext.current
    // 读文件与整图解码都离开主线程：组合期同步做这些会让滚动/流式 delta 掉帧。
    val image by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = if (uri.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                        input.readBytes()
                    }
                } else {
                    File(uri).takeIf { it.isFile }?.readBytes()
                } ?: return@withContext null
                decodeImageBitmap(bytes)
            }.getOrNull()
        }
    }
    return image
}
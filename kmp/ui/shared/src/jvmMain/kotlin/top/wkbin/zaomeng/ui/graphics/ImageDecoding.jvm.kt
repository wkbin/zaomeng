package top.wkbin.zaomeng.ui.graphics

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import top.wkbin.zaomeng.platform.SillyTavernPng

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? = try {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
} catch (error: Throwable) {
    null
}

actual fun encodeAvatarAsPng(bytes: ByteArray): ByteArray =
    Image.makeFromEncoded(bytes).use { image ->
        require(SillyTavernPng.fitsDimensions(image.width, image.height)) { "头像尺寸过大。" }
        requireNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "无法生成 PNG 头像。" }
            .use { it.bytes }
    }

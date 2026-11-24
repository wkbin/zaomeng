package top.wkbin.zaomeng.ui.graphics

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import top.wkbin.zaomeng.platform.SillyTavernPng

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? = try {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
} catch (error: Throwable) {
    null
}

actual fun encodeAvatarAsPng(bytes: ByteArray): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取头像图片。" }
    require(SillyTavernPng.fitsDimensions(bounds.outWidth, bounds.outHeight)) { "头像尺寸过大。" }
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "无法读取头像图片。" }
    try {
        val output = ByteArrayOutputStream()
        require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法生成 PNG 头像。" }
        return output.toByteArray()
    } finally {
        bitmap.recycle()
    }
}

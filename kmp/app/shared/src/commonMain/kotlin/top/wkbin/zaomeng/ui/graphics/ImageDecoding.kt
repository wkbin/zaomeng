package top.wkbin.zaomeng.ui.graphics

import androidx.compose.ui.graphics.ImageBitmap

/** 解码头像等图片字节为 Compose ImageBitmap（Android 走 BitmapFactory，JVM/iOS 走 Skia）。 */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?

@file:OptIn(ExperimentalForeignApi::class)

package top.wkbin.zaomeng.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGContextAddEllipseInRect
import platform.CoreGraphics.CGContextClip
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy

/**
 * iOS 头像裁剪：从原图取 (left, top, side×side) 正方形区域，缩放到 512×512
 * 并裁成圆形，输出 PNG（与 Android 行为一致）。
 */
actual fun cropAvatarBytes(bytes: ByteArray, side: Int, left: Int, top: Int): ByteArray {
    val source = UIImage(data = bytes.toNSData()) ?: error("无法读取所选图片。")
    val (sourceWidth, sourceHeight) = source.size.useContents { width to height }
    val safeSide = side.coerceIn(1, minOf(sourceWidth.toInt(), sourceHeight.toInt()))
    require(safeSide > 0) { "所选图片无效。" }
    val safeLeft = left.coerceIn(0, sourceWidth.toInt() - safeSide)
    val safeTop = top.coerceIn(0, sourceHeight.toInt() - safeSide)

    val scale = 512.0 / safeSide
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(512.0, 512.0), false, 1.0)
    val context = UIGraphicsGetCurrentContext()
    if (context == null) {
        UIGraphicsEndImageContext()
        error("无法创建图片上下文。")
    }
    CGContextSaveGState(context)
    CGContextAddEllipseInRect(context, CGRectMake(0.0, 0.0, 512.0, 512.0))
    CGContextClip(context)
    // drawInRect 使用 UIKit 坐标系（左上原点）并尊重图片方向，直接画裁剪区域。
    source.drawInRect(
        CGRectMake(
            -safeLeft.toDouble() * scale,
            -safeTop.toDouble() * scale,
            sourceWidth * scale,
            sourceHeight * scale,
        ),
    )
    CGContextRestoreGState(context)
    val cropped = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    val pngData = cropped?.let { UIImagePNGRepresentation(it) } ?: error("裁剪头像失败。")
    return pngData.toByteArray()
}

private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

private fun NSData.toByteArray(): ByteArray {
    val result = ByteArray(length.toInt())
    if (result.isNotEmpty()) {
        result.usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, length)
        }
    }
    return result
}

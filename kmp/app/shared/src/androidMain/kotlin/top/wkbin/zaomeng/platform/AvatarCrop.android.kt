package top.wkbin.zaomeng.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import java.io.ByteArrayOutputStream

actual fun cropAvatarBytes(bytes: ByteArray, side: Int, left: Int, top: Int): ByteArray {
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: error("无法读取所选图片。")
    val safeSide = side.coerceIn(1, minOf(source.width, source.height))
    require(safeSide > 0) { "所选图片无效。" }
    val safeLeft = left.coerceIn(0, source.width - safeSide)
    val safeTop = top.coerceIn(0, source.height - safeSide)
    val circular = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    Canvas(circular).apply {
        save()
        clipPath(Path().apply { addCircle(256f, 256f, 256f, Path.Direction.CW) })
        drawBitmap(
            source,
            Rect(safeLeft, safeTop, safeLeft + safeSide, safeTop + safeSide),
            Rect(0, 0, 512, 512),
            Paint(Paint.ANTI_ALIAS_FLAG),
        )
        restore()
    }
    return ByteArrayOutputStream().use { output ->
        circular.compress(Bitmap.CompressFormat.PNG, 100, output)
        circular.recycle()
        source.recycle()
        output.toByteArray()
    }
}

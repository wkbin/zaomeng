package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** 按 Uri/路径加载图片（Android content://；桌面/iOS 本地文件路径）；失败返回 null。 */
@Composable
expect fun rememberPlatformImage(uri: String): ImageBitmap?

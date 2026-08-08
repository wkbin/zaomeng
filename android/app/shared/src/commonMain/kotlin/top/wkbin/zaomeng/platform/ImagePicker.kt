package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

/** 选择一张本地图片，回调其 Uri/路径字符串（Android content://，桌面/iOS 本地文件路径）。 */
@Composable
expect fun rememberImagePicker(onPicked: (uri: String) -> Unit): () -> Unit

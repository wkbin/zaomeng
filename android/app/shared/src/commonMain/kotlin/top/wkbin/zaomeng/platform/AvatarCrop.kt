package top.wkbin.zaomeng.platform

/** 按裁剪参数把头图片裁成 512x512（Android 圆形裁剪；桌面/iOS 暂返回原图 TODO）。 */
expect fun cropAvatarBytes(bytes: ByteArray, side: Int, left: Int, top: Int): ByteArray

package top.wkbin.zaomeng.platform

/** 按裁剪参数把头图片裁成 512x512 圆形（Android/iOS；桌面暂返回原图）。 */
expect fun cropAvatarBytes(bytes: ByteArray, side: Int, left: Int, top: Int): ByteArray

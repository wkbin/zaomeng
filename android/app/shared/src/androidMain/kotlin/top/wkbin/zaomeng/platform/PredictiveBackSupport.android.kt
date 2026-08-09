package top.wkbin.zaomeng.platform

import android.os.Build

actual fun predictiveBackSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

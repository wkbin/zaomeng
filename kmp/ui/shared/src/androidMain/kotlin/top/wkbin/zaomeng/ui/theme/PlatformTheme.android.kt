package top.wkbin.zaomeng.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
actual fun applySystemBars(darkTheme: Boolean, windowBackground: Color) {
    val context = LocalContext.current
    val view = LocalView.current
    SideEffect {
        val window = (context as Activity).window
        // 窗口背景跟随主题色，避免预测返回/转场透出浅色底
        window.decorView.setBackgroundColor(windowBackground.toArgb())
        // 关闭系统在导航栏上自动叠加的对比度遮罩，让内容真正铺满（参考 KernelSU 边缘到边缘）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

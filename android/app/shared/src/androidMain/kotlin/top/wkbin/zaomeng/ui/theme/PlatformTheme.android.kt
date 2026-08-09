package top.wkbin.zaomeng.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.materialkolor.rememberDynamicColorScheme

@Composable
actual fun platformColorScheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    seedColorArgb: Long,
): ColorScheme? {
    if (!dynamicColor) return null
    if (seedColorArgb != 0L) {
        return rememberDynamicColorScheme(
            seedColor = Color(seedColorArgb),
            isDark = darkTheme,
        )
    }
    // 未指定种子色时，Android 12+ 跟随系统壁纸取色；低版本退回静态方案。
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

@Composable
actual fun applySystemBars(darkTheme: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    SideEffect {
        val window = (context as Activity).window
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

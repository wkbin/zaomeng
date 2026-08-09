package top.wkbin.zaomeng.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.rememberDynamicColorScheme

@Composable
actual fun platformColorScheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    seedColorArgb: Long,
): ColorScheme? {
    if (!dynamicColor || seedColorArgb == 0L) return null
    return rememberDynamicColorScheme(
        seedColor = Color(seedColorArgb),
        isDark = darkTheme,
    )
}

@Composable
actual fun applySystemBars(darkTheme: Boolean, windowBackground: Color) = Unit

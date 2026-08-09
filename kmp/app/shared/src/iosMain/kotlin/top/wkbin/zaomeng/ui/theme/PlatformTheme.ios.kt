package top.wkbin.zaomeng.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.rememberDynamicColorScheme

/** iOS 无系统动态色，指定种子色时用 MaterialKolor 生成动态取色；否则走统一 Material 主题。 */
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

/** iOS 系统栏由系统管理，无需适配。 */
@Composable
actual fun applySystemBars(darkTheme: Boolean, windowBackground: Color) = Unit

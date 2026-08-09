package top.wkbin.zaomeng.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import top.wkbin.zaomeng.data.preferences.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun MyApplicationTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    themeSeedColorArgb: Long = 0L,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM, ThemeMode.MONET_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT, ThemeMode.MONET_LIGHT -> false
        ThemeMode.DARK, ThemeMode.MONET_DARK -> true
    }
    // 动态取色仅在 Monet 模式下启用，与 KernelSU 语义一致。
    val colorScheme = platformColorScheme(
        darkTheme = darkTheme,
        dynamicColor = themeMode.isMonet,
        seedColorArgb = themeSeedColorArgb,
    )
        ?: if (darkTheme) DarkColorScheme else LightColorScheme
    applySystemBars(darkTheme, colorScheme.background)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}

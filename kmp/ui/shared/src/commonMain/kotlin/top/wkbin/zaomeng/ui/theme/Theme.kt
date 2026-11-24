package top.wkbin.zaomeng.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.wkbin.zaomeng.data.preferences.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9ED3C1),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF1D5042),
    onPrimaryContainer = Color(0xFFBAF0DC),
    secondary = Color(0xFFB4CCBE),
    onSecondary = Color(0xFF20352B),
    secondaryContainer = Color(0xFF354C41),
    onSecondaryContainer = Color(0xFFD0E8DA),
    tertiary = Color(0xFFE1C68F),
    onTertiary = Color(0xFF3D2E0A),
    tertiaryContainer = Color(0xFF554519),
    onTertiaryContainer = Color(0xFFFEE2A9),
    background = Color(0xFF111815),
    onBackground = Color(0xFFDFE4DF),
    surface = Color(0xFF111815),
    onSurface = Color(0xFFDFE4DF),
    onSurfaceVariant = Color(0xFFBEC9C0),
    surfaceContainerLow = Color(0xFF19221E),
    surfaceContainer = Color(0xFF1D2822),
    surfaceContainerHigh = Color(0xFF28332D),
    surfaceContainerHighest = Color(0xFF334039),
    surfaceVariant = Color(0xFF3E4942),
    outline = Color(0xFF89968D),
    outlineVariant = Color(0xFF3E4942),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF266554),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E9DB),
    onPrimaryContainer = Color(0xFF0A392D),
    secondary = Color(0xFF4A6357),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E9DF),
    onSecondaryContainer = Color(0xFF1C372B),
    tertiary = Color(0xFF725900),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF5E4B5),
    onTertiaryContainer = Color(0xFF342700),
    background = Color(0xFFF7F8F3),
    onBackground = Color(0xFF1B2420),
    surface = Color(0xFFF7F8F3),
    onSurface = Color(0xFF1B2420),
    onSurfaceVariant = Color(0xFF47534C),
    surfaceContainerLow = Color(0xFFEEF2EB),
    surfaceContainer = Color(0xFFE8EEE7),
    surfaceContainerHigh = Color(0xFFE0E8DF),
    surfaceContainerHighest = Color(0xFFD8E0D7),
    surfaceVariant = Color(0xFFDFE8DF),
    outline = Color(0xFF6E7A72),
    outlineVariant = Color(0xFFBECBC1),
)


@Composable
fun ZaomengTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColorEnabled: Boolean = false,
    themeSeedColorArgb: Long = 0L,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = platformColorScheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColorEnabled,
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

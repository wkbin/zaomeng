package top.wkbin.zaomeng.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import io.github.suqi8.coui.kmp.effect.LocalCOUIMaterialEffectEnabled
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.COUIThemeColor
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.LocalContentColor
import io.github.suqi8.coui.kmp.theme.ThemeController
import io.github.suqi8.coui.kmp.theme.ThemePaletteStyle
import top.wkbin.zaomeng.data.preferences.ThemeMode

/** 应用外观偏好由 COUI 主题控制器解析，系统栏使用同一套色板。 */
@Composable
fun ZaomengTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColorEnabled: Boolean = false,
    themeSeedColorArgb: Long = 0L,
    content: @Composable () -> Unit,
) {
    val controller = remember(themeMode, dynamicColorEnabled, themeSeedColorArgb) {
        val keyColor = if (themeSeedColorArgb != 0L) Color(themeSeedColorArgb) else null
        ThemeController(
            colorSchemeMode = when {
                !dynamicColorEnabled -> when (themeMode) {
                    ThemeMode.SYSTEM -> ColorSchemeMode.System
                    ThemeMode.LIGHT -> ColorSchemeMode.Light
                    ThemeMode.DARK -> ColorSchemeMode.Dark
                }

                else -> when (themeMode) {
                    ThemeMode.SYSTEM -> ColorSchemeMode.MonetSystem
                    ThemeMode.LIGHT -> ColorSchemeMode.MonetLight
                    ThemeMode.DARK -> ColorSchemeMode.MonetDark
                }
            },
            keyColor = keyColor,
            paletteStyle = ThemePaletteStyle.Content,
            themeColor = keyColor?.takeUnless { dynamicColorEnabled }?.let {
                ThemeSeedColor.fromArgb(themeSeedColorArgb)?.couiThemeColor
                    ?: COUIThemeColor.fromColor(it)
            },
        )
    }

    COUITheme(controller = controller) {
        val colors = COUITheme.colorScheme
        applySystemBars(colors.isDark, colors.backgroundWithCard)
        // 打开 COUI 17 的材质层：描边光、内阴影、触摸聚光。
        //
        // COUI 组件内部会用 isRuntimeShaderSupported() 自查能力，不支持的平台（Android 13 以下）
        // 自动退回无材质绘制，因此这里可以直接打开；项目 minSdk 24 不影响。
        // 背景模糊（backdrop blur）另需 coui-blur（要求 minSdk 33），未引入时 COUI 走不透明填充，
        // 需要通透感的悬浮层请用半透明底色（COUI 会自动叠加 ColorOS 的明暗混合层）。
        CompositionLocalProvider(
            LocalCOUIMaterialEffectEnabled provides true,
            // 根部补一层内容色，让不在 COUI Surface 内的文字也有正确的默认前景色。
            LocalContentColor provides colors.labelPrimary,
            content = content,
        )
    }
}

package top.wkbin.zaomeng.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/**
 * 平台主题钩子：Android 提供 Material You 动态色与状态栏适配；桌面端忽略。
 * 非 null 时优先使用（Android 12+ 且启用 dynamicColor）。
 */
@Composable
expect fun platformColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme?

/** 应用系统栏（状态栏/导航栏）深浅适配；桌面端 no-op。 */
@Composable
expect fun applySystemBars(darkTheme: Boolean)

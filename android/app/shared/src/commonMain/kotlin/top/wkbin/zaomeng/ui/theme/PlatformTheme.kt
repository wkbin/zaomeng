package top.wkbin.zaomeng.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 平台主题钩子：Monet 模式下生成 Material You 动态色。
 * Android 12+ 且未指定种子色时跟随系统壁纸取色；指定种子色时（Android/桌面/iOS）
 * 统一用 MaterialKolor 由种子色生成。非 null 时优先使用。
 */
@Composable
expect fun platformColorScheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    seedColorArgb: Long,
): ColorScheme?

/**
 * 应用系统栏（状态栏/导航栏）深浅适配，并把窗口背景设为当前主题背景色，
 * 避免预测返回/转场/启动时透出固定浅色底（部分厂商的返回预览直接取窗口底色）。
 * 桌面端/iOS no-op。
 */
@Composable
expect fun applySystemBars(darkTheme: Boolean, windowBackground: Color)

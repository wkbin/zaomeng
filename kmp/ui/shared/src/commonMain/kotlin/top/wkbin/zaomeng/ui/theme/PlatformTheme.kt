package top.wkbin.zaomeng.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 应用系统栏（状态栏/导航栏）深浅适配，并把窗口背景设为当前主题背景色，
 * 避免预测返回/转场/启动时透出固定浅色底（部分厂商的返回预览直接取窗口底色）。
 * 桌面端/iOS no-op。
 *
 * 动态取色已交由 COUI 的 [io.github.suqi8.coui.kmp.theme.ThemeController]（Monet 模式）负责，
 * 这里不再保留 Material You 的 `platformColorScheme`。
 */
@Composable
expect fun applySystemBars(darkTheme: Boolean, windowBackground: Color)

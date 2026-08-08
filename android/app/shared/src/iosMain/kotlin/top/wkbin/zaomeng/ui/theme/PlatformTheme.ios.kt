package top.wkbin.zaomeng.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** iOS 无 Material You 动态色，走统一 Material 主题。 */
@Composable
actual fun platformColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme? = null

/** iOS 系统栏由系统管理，无需适配。 */
@Composable
actual fun applySystemBars(darkTheme: Boolean) = Unit

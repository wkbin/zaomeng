package top.wkbin.zaomeng.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** iOS 系统栏由系统管理，无需适配。 */
@Composable
actual fun applySystemBars(darkTheme: Boolean, windowBackground: Color) = Unit

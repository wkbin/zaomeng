package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

/** 用系统浏览器/外部应用打开 URL（http/https/mailto 等）。 */
@Composable
expect fun rememberOpenExternalUrl(): (String) -> Unit

/** 短暂提示（Android Toast；桌面系统托盘通知；iOS 瞬时弹窗）。 */
@Composable
expect fun rememberToast(): (String) -> Unit

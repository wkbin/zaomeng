package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

/** 系统返回手势/按键处理：Android 接 BackHandler，桌面/iOS no-op（由导航宿主处理返回）。 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

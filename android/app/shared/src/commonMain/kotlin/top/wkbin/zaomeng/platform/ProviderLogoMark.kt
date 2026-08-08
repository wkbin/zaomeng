package top.wkbin.zaomeng.platform

import androidx.compose.runtime.Composable

/** 模型提供商品牌图标（Android 用 drawable 资源；桌面/iOS 用通用图标）。 */
@Composable
expect fun ProviderLogoMark(catalogId: String)

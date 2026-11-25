package top.wkbin.zaomeng.platform

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.theme.COUITheme
import org.jetbrains.compose.resources.painterResource
import zaomeng.ui.shared.generated.resources.Res
import zaomeng.ui.shared.generated.resources.ic_provider_anthropic
import zaomeng.ui.shared.generated.resources.ic_provider_deepseek
import zaomeng.ui.shared.generated.resources.ic_provider_jieyue
import zaomeng.ui.shared.generated.resources.ic_provider_mimo
import zaomeng.ui.shared.generated.resources.ic_provider_ollama
import zaomeng.ui.shared.generated.resources.ic_provider_openai
import zaomeng.ui.shared.generated.resources.ic_provider_qwen

/** 模型提供商品牌图标：统一走 composeResources（Android/桌面/iOS 三端一致）。 */
@Composable
fun ProviderLogoMark(catalogId: String) {
    val drawable = when (catalogId) {
        "deepseek" -> Res.drawable.ic_provider_deepseek
        "qwen" -> Res.drawable.ic_provider_qwen
        "mimo" -> Res.drawable.ic_provider_mimo
        "stepfun" -> Res.drawable.ic_provider_jieyue
        "anthropic" -> Res.drawable.ic_provider_anthropic
        "openai" -> Res.drawable.ic_provider_openai
        "ollama" -> Res.drawable.ic_provider_ollama
        else -> null
    }
    if (drawable != null) {
        Image(painterResource(drawable), null, Modifier.height(24.dp))
    } else {
        Icon(COUIIcons.Tune, null, tint = COUITheme.colorScheme.labelSecondary)
    }
}

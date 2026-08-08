package top.wkbin.zaomeng.platform

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.app.shared.R

@Composable
actual fun ProviderLogoMark(catalogId: String) {
    val drawable = when (catalogId) {
        "deepseek" -> R.drawable.ic_provider_deepseek
        "qwen" -> R.drawable.ic_provider_qwen
        "mimo" -> R.drawable.ic_provider_mimo
        "stepfun" -> R.drawable.ic_provider_jieyue
        "anthropic" -> R.drawable.ic_provider_anthropic
        "openai" -> R.drawable.ic_provider_openai
        "ollama" -> R.drawable.ic_provider_ollama
        else -> null
    }
    if (drawable != null) {
        Image(painterResource(drawable), null, Modifier.height(24.dp))
    } else {
        Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

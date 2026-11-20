package top.wkbin.zaomeng.platform

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberShareText(): (text: String) -> Unit {
    val context = LocalContext.current
    return remember {
        { text ->
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            // Android 11+ 未声明 <queries> 时 resolveActivity 恒为 null（即使有可用分享目标），
            // 直接交给系统 chooser；真的没有目标时由 ActivityNotFoundException 兜底。
            runCatching {
                context.startActivity(Intent.createChooser(sendIntent, "分享"))
            }
        }
    }
}

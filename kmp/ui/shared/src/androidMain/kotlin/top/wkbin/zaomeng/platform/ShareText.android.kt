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
            if (sendIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(Intent.createChooser(sendIntent, "分享"))
            }
        }
    }
}

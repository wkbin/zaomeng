package top.wkbin.zaomeng.platform

import android.content.ClipData
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberClipboardTextWriter(): suspend (String) -> Unit {
    val appContext = LocalContext.current.applicationContext
    return remember {
        { text ->
            val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("zaomeng", text))
        }
    }
}

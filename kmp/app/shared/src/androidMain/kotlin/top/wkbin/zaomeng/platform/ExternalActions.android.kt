package top.wkbin.zaomeng.platform

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberOpenExternalUrl(): (String) -> Unit {
    val context = LocalContext.current
    return remember {
        { url ->
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}

@Composable
actual fun rememberToast(): (String) -> Unit {
    val context = LocalContext.current
    return remember {
        { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
}

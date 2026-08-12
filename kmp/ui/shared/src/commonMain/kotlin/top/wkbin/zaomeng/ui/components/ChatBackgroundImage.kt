package top.wkbin.zaomeng.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.platform.rememberPlatformImage

@Composable
fun ChatBackgroundImage(
    imageUri: String,
    opacity: Float,
    blurRadius: Float,
    modifier: Modifier = Modifier,
) {
    if (imageUri.isBlank()) return
    val bitmap = rememberPlatformImage(imageUri) ?: return
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = opacity.coerceIn(0.1f, 1f),
        modifier = modifier
            .fillMaxSize()
            .blur(blurRadius.coerceIn(0f, 32f).dp),
    )
}

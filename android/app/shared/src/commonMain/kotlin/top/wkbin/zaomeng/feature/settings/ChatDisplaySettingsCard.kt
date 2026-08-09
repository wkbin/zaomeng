package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.CHAT_FONT_SCALE_MAX
import top.wkbin.zaomeng.data.preferences.CHAT_FONT_SCALE_MIN
import top.wkbin.zaomeng.data.preferences.ChatDisplayPreferences
import top.wkbin.zaomeng.feature.chat.ChatBackgroundImage
import top.wkbin.zaomeng.platform.rememberImagePicker

@Composable
fun ChatDisplaySettingsCard(
    modifier: Modifier = Modifier,
    preferencesRepository: AppPreferencesRepository = koinInject(),
) {
    val preferences by preferencesRepository.chatDisplayPreferences.collectAsStateWithLifecycle(
        initialValue = ChatDisplayPreferences(),
    )
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var backgroundOpacity by remember { mutableFloatStateOf(preferences.backgroundOpacity) }
    var backgroundBlurRadius by remember { mutableFloatStateOf(preferences.backgroundBlurRadius) }
    var fontSizeScale by remember { mutableFloatStateOf(preferences.fontSizeScale) }
    var compactMode by remember { mutableStateOf(preferences.compactMode) }

    LaunchedEffect(preferences.backgroundOpacity) { backgroundOpacity = preferences.backgroundOpacity }
    LaunchedEffect(preferences.backgroundBlurRadius) { backgroundBlurRadius = preferences.backgroundBlurRadius }
    LaunchedEffect(preferences.fontSizeScale) { fontSizeScale = preferences.fontSizeScale }
    LaunchedEffect(preferences.compactMode) { compactMode = preferences.compactMode }

    fun persist(change: suspend () -> Unit) {
        if (saving) return
        scope.launch {
            saving = true
            error = ""
            try {
                change()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "聊天显示设置保存失败。"
            } finally {
                saving = false
            }
        }
    }

    val backgroundPicker = rememberImagePicker { uri ->
        persist { preferencesRepository.setChatBackgroundImageUri(uri) }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsGroupCard {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("消息字号", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "拖动滑杆调整对话文字大小，效果在下方聊天背景预览中实时查看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${(fontSizeScale * 100).toInt()}%",
                    modifier = Modifier.align(Alignment.End),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = fontSizeScale,
                    onValueChange = { fontSizeScale = it },
                    onValueChangeFinished = {
                        persist { preferencesRepository.setChatFontSize(fontSizeScale) }
                    },
                    valueRange = CHAT_FONT_SCALE_MIN..CHAT_FONT_SCALE_MAX,
                    enabled = !saving,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingSwitchRow(
                title = "紧凑显示",
                description = "缩小消息间距，在一屏内查看更多内容。",
                checked = compactMode,
                enabled = !saving,
                onCheckedChange = { checked ->
                    compactMode = checked
                    persist { preferencesRepository.setCompactChatMode(checked) }
                },
            )
        }

        SettingsGroupCard {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("聊天背景", style = MaterialTheme.typography.titleMedium)
                Text(
                    "预览同时反映背景、字号与紧凑模式，拖动上方滑杆或开关会实时更新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChatBackgroundPreview(
                    imageUri = preferences.backgroundImageUri,
                    opacity = backgroundOpacity,
                    blurRadius = backgroundBlurRadius,
                    fontSizeScale = fontSizeScale,
                    compactMode = compactMode,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { backgroundPicker() },
                        enabled = !saving,
                    ) { Text(if (preferences.backgroundImageUri.isBlank()) "选择图片" else "更换图片") }
                    if (preferences.backgroundImageUri.isNotBlank()) {
                        OutlinedButton(
                            onClick = { persist { preferencesRepository.setChatBackgroundImageUri("") } },
                            enabled = !saving,
                        ) { Text("移除") }
                    }
                }
                if (preferences.backgroundImageUri.isNotBlank()) {
                    Text("背景可见度 ${(backgroundOpacity * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = backgroundOpacity,
                        onValueChange = { backgroundOpacity = it },
                        onValueChangeFinished = {
                            persist { preferencesRepository.setChatBackgroundOpacity(backgroundOpacity) }
                        },
                        valueRange = 0.1f..1f,
                        enabled = !saving,
                    )
                    Text("背景模糊 ${backgroundBlurRadius.toInt()} dp", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = backgroundBlurRadius,
                        onValueChange = { backgroundBlurRadius = it },
                        onValueChangeFinished = {
                            persist { preferencesRepository.setChatBackgroundBlurRadius(backgroundBlurRadius) }
                        },
                        valueRange = 0f..32f,
                        enabled = !saving,
                    )
                }
            }
        }

        SettingsGroupCard {
            SettingSwitchRow(
                title = "显示模型推理",
                description = "在聊天中显示模型返回的推理文本，默认关闭。",
                checked = preferences.showModelReasoning,
                enabled = !saving,
                onCheckedChange = { persist { preferencesRepository.setShowModelReasoning(it) } },
            )
        }

        if (error.isNotBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun SettingsGroupCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { Column { content() } }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun ChatBackgroundPreview(imageUri: String, opacity: Float, blurRadius: Float) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {}
        ChatBackgroundImage(imageUri = imageUri, opacity = opacity, blurRadius = blurRadius)
        if (imageUri.isBlank()) {
            Text(
                "选择图片后在这里预览",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                ) { Text("今晚的雨似乎不会停。", Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                Surface(
                    modifier = Modifier.align(Alignment.End),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                ) { Text("那就再等一会儿。", Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
            }
        }
    }
}

/** 聊天背景预览：同时反映背景图片、字号缩放与紧凑模式（无背景图时也能预览文字效果）。 */
@Composable
private fun ChatBackgroundPreview(
    imageUri: String,
    opacity: Float,
    blurRadius: Float,
    fontSizeScale: Float,
    compactMode: Boolean,
) {
    val shape = RoundedCornerShape(10.dp)
    val verticalPadding = if (compactMode) 5.dp else 9.dp
    val bubbleSpacing = if (compactMode) 4.dp else 10.dp
    val messageStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = MaterialTheme.typography.bodyMedium.fontSize * fontSizeScale,
    )
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {}
        ChatBackgroundImage(imageUri = imageUri, opacity = opacity, blurRadius = blurRadius)
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(bubbleSpacing),
        ) {
            Surface(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    "旁白 · 雨夜茶馆",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.End),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    "今晚的雨似乎不会停。",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = verticalPadding),
                    style = messageStyle,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.Start),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    "那就再等一会儿，等雨把街灯洗亮。",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = verticalPadding),
                    style = messageStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

package top.wkbin.zaomeng.feature.settings

import top.wkbin.zaomeng.ui.theme.chatMessageTint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.preference.SliderPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okio.Path
import org.koin.compose.koinInject
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.CHAT_FONT_SCALE_MAX
import top.wkbin.zaomeng.data.preferences.CHAT_FONT_SCALE_MIN
import top.wkbin.zaomeng.data.preferences.ChatDisplayPreferences
import top.wkbin.zaomeng.platform.rememberImagePicker
import top.wkbin.zaomeng.ui.components.ChatBackgroundImage

@Composable
fun ChatDisplaySettingsCard(
    filesDir: Path,
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

    val backgroundPicker = rememberImagePicker(filesDir) { uri ->
        persist { preferencesRepository.setChatBackgroundImageUri(uri) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Card(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            CardListGroup {
                item {
                    SliderPreference(
                        title = "消息字号",
                        summary = "拖动滑杆调整对话文字大小，效果在下方聊天背景预览中实时查看。",
                        value = fontSizeScale,
                        onValueChange = { fontSizeScale = it },
                        onValueChangeFinished = {
                            persist { preferencesRepository.setChatFontSize(fontSizeScale) }
                        },
                        valueText = "${(fontSizeScale * 100).toInt()}%",
                        valueRange = CHAT_FONT_SCALE_MIN..CHAT_FONT_SCALE_MAX,
                        enabled = !saving,
                    )
                }
                item {
                    SwitchPreference(
                        title = "紧凑显示",
                        summary = "缩小消息间距，在一屏内查看更多内容。",
                        checked = compactMode,
                        enabled = !saving,
                        onCheckedChange = { checked ->
                            compactMode = checked
                            persist { preferencesRepository.setCompactChatMode(checked) }
                        },
                    )
                }
            }
        }

        Card(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            CardListGroup {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("聊天背景", style = COUITheme.textStyles.headlineXS)
                        Text(
                            "预览同时反映背景、字号与紧凑模式，拖动上方滑杆或开关会实时更新。",
                            style = COUITheme.textStyles.bodyS,
                            color = COUITheme.colorScheme.labelSecondary,
                        )
                        ChatBackgroundPreview(
                            imageUri = preferences.backgroundImageUri,
                            opacity = backgroundOpacity,
                            blurRadius = backgroundBlurRadius,
                            fontSizeScale = fontSizeScale,
                            compactMode = compactMode,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                text = if (preferences.backgroundImageUri.isBlank()) "选择图片" else "更换图片",
                                onClick = { backgroundPicker() },
                                enabled = !saving,
                            )
                            if (preferences.backgroundImageUri.isNotBlank()) {
                                TextButton(
                                    text = "移除",
                                    onClick = { persist { preferencesRepository.setChatBackgroundImageUri("") } },
                                    enabled = !saving,
                                )
                            }
                        }
                    }
                }
                if (preferences.backgroundImageUri.isNotBlank()) {
                    item {
                        SliderPreference(
                            title = "背景可见度",
                            value = backgroundOpacity,
                            onValueChange = { backgroundOpacity = it },
                            onValueChangeFinished = {
                                persist { preferencesRepository.setChatBackgroundOpacity(backgroundOpacity) }
                            },
                            valueRange = 0.1f..1f,
                            valueText = "${(backgroundOpacity * 100).toInt()}%",
                            enabled = !saving,
                        )
                    }
                    item {
                        SliderPreference(
                            title = "背景模糊",
                            value = backgroundBlurRadius,
                            onValueChange = { backgroundBlurRadius = it },
                            onValueChangeFinished = {
                                persist { preferencesRepository.setChatBackgroundBlurRadius(backgroundBlurRadius) }
                            },
                            valueRange = 0f..32f,
                            valueText = "${backgroundBlurRadius.toInt()} dp",
                            enabled = !saving,
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            CardListGroup {
                item {
                    SwitchPreference(
                        title = "显示模型推理",
                        summary = "在聊天中显示模型返回的推理文本，默认关闭。",
                        checked = preferences.showModelReasoning,
                        enabled = !saving,
                        onCheckedChange = { persist { preferencesRepository.setShowModelReasoning(it) } },
                    )
                }
            }
        }

        if (error.isNotBlank()) {
            Text(
                text = error,
                style = COUITheme.textStyles.bodyS,
                color = COUITheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun ChatBackgroundPreview(imageUri: String, opacity: Float, blurRadius: Float) {
    val shape = SquircleShape(COUIRoundCorner.Normal.S, COUIRoundCorner.Normal.Weight)
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = COUITheme.colorScheme.surface) {}
        ChatBackgroundImage(imageUri = imageUri, opacity = opacity, blurRadius = blurRadius)
        if (imageUri.isBlank()) {
            Text(
                "选择图片后在这里预览",
                modifier = Modifier.align(Alignment.Center),
                style = COUITheme.textStyles.bodyS,
                color = COUITheme.colorScheme.labelSecondary,
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = SquircleShape(COUIRoundCorner.Normal.S, COUIRoundCorner.Normal.Weight),
                    color = COUITheme.colorScheme.cardBackground.copy(alpha = 0.88f),
                ) {
                    Text(
                        "今晚的雨似乎不会停。",
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = COUITheme.textStyles.bodyM,
                        color = COUITheme.colorScheme.labelPrimary,
                    )
                }
                Surface(
                    modifier = Modifier.align(Alignment.End),
                    shape = SquircleShape(COUIRoundCorner.Normal.S, COUIRoundCorner.Normal.Weight),
                    color = COUITheme.colorScheme.chatMessageTint(),
                ) {
                    Text(
                        "那就再等一会儿。",
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = COUITheme.textStyles.bodyM,
                        color = COUITheme.colorScheme.labelPrimary,
                    )
                }
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
    val shape = SquircleShape(COUIRoundCorner.Normal.S, COUIRoundCorner.Normal.Weight)
    val verticalPadding = if (compactMode) 5.dp else 9.dp
    val bubbleSpacing = if (compactMode) 4.dp else 10.dp
    val messageStyle = COUITheme.textStyles.bodyM.copy(
        fontSize = COUITheme.textStyles.bodyM.fontSize * fontSizeScale,
    )
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = COUITheme.colorScheme.surface) {}
        ChatBackgroundImage(imageUri = imageUri, opacity = opacity, blurRadius = blurRadius)
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(bubbleSpacing),
        ) {
            Surface(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = COUITheme.colorScheme.containerThemeHalftone.copy(alpha = 0.75f),
                shape = SquircleShape(COUIRoundCorner.Normal.S, COUIRoundCorner.Normal.Weight),
            ) {
                Text(
                    "旁白 · 雨夜茶馆",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.End),
                color = COUITheme.colorScheme.chatMessageTint(),
                shape = SquircleShape(COUIRoundCorner.Normal.S, COUIRoundCorner.Normal.Weight),
            ) {
                Text(
                    "今晚的雨似乎不会停。",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = verticalPadding),
                    style = messageStyle,
                    color = COUITheme.colorScheme.labelPrimary,
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.Start),
                color = COUITheme.colorScheme.backgroundWithCard.copy(alpha = 0.92f),
                shape = SquircleShape(COUIRoundCorner.Normal.S, COUIRoundCorner.Normal.Weight),
            ) {
                Text(
                    "那就再等一会儿，等雨把街灯洗亮。",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = verticalPadding),
                    style = messageStyle,
                    color = COUITheme.colorScheme.labelPrimary,
                )
            }
        }
    }
}

package top.wkbin.zaomeng.feature.chat

import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.ui.roleplay.RoleplayMessageContent
import top.wkbin.zaomeng.ui.theme.chatMessageTint

import top.wkbin.zaomeng.ui.components.ChatBackgroundImage
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.icon.extended.Play
import io.github.suqi8.coui.kmp.icon.extended.VolumeOff
import io.github.suqi8.coui.kmp.icon.extended.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.ChatSearchResultDto
import top.wkbin.zaomeng.data.api.TranscriptItemDto
import top.wkbin.zaomeng.data.preferences.ChatDisplayPreferences
import top.wkbin.zaomeng.feature.chat.transcript.EmotionBadge
import top.wkbin.zaomeng.feature.chat.insights.consistencyInsight
import top.wkbin.zaomeng.platform.PlatformBackHandler
import top.wkbin.zaomeng.platform.rememberClipboardTextWriter
import top.wkbin.zaomeng.platform.rememberPlatformImage
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun TranscriptBubble(
    item: TranscriptItemDto,
    avatarBytes: Map<String, ByteArray>,
    displayPreferences: ChatDisplayPreferences,
    actionsEnabled: Boolean,
    includeInnerThoughts: Boolean,
    readingLayout: Boolean = false,
    streaming: Boolean = false,
    canRegenerate: Boolean = false,
    isSpeaking: Boolean = false,
    speakingId: String? = null,
    onSpeak: (() -> Unit)? = null,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit,
) {
    val isUser = item.role == "user"
    val isScene = item.role == "scene"
    val isDirector = item.role == "director"
    val canBranch = item.turnId.isNotBlank()
    var menuExpanded by remember(item.turnId, item.message) { mutableStateOf(false) }
    val verticalPadding = if (displayPreferences.compactMode) 6.dp else 9.dp
    val messageStyle = COUITheme.textStyles.bodyM.copy(
        fontSize = COUITheme.textStyles.bodyM.fontSize * displayPreferences.fontSizeScale,
    )

    if (readingLayout && !isUser && !isDirector) {
        val body = COUITheme.textStyles.bodyXL
        val scale = displayPreferences.fontSizeScale
        val readingStyle = body.copy(fontSize = body.fontSize * scale, lineHeight = body.lineHeight * scale)
        val isThisSpeaking = isSpeaking && speakingId == item.transcriptKey()
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!isScene) {
                        ChatPersonaAvatar(
                            bytes = avatarBytes[item.speaker],
                            name = item.speaker,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    val speaker = if (isScene) {
                        item.speaker.takeIf(String::isNotBlank)?.let { "旁白 · $it" } ?: "旁白"
                    } else item.speaker.ifBlank { "人物" }
                    Text(
                        text = speaker,
                        modifier = Modifier.weight(1f),
                        style = COUITheme.textStyles.buttonM,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                    if (onSpeak != null && !isScene && !streaming) {
                        IconButton(onClick = onSpeak) {
                            Icon(
                                imageVector = if (isThisSpeaking) COUIIcons.VolumeOff
                                else COUIIcons.VolumeUp,
                                contentDescription = if (isThisSpeaking) "停止朗读" else "朗读",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    if (!streaming) {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(COUIIcons.More, contentDescription = "消息操作", modifier = Modifier.size(20.dp))
                        }
                    }
                }
                RoleplayMessageContent(
                    text = item.message,
                    style = readingStyle,
                    baseColor = COUITheme.colorScheme.labelPrimary,
                    modifier = Modifier.fillMaxWidth().then(
                        if (streaming) Modifier else Modifier.combinedClickable(
                            onClick = {},
                            onLongClickLabel = "消息操作",
                            onLongClick = { menuExpanded = true },
                        ),
                    ),
                    streaming = streaming,
                    readingLayout = true,
                )
                if (includeInnerThoughts && item.innerThought.isNotBlank() && !isScene) {
                    var thoughtExpanded by rememberSaveable { mutableStateOf(false) }
                    TextButton(
                        text = if (thoughtExpanded) "收起内心独白" else "展开内心独白",
                        onClick = { thoughtExpanded = !thoughtExpanded },
                        minWidth = 0.dp,
                        textStyle = COUITheme.textStyles.bodyM,
                    )
                    if (thoughtExpanded) {
                        Text(item.innerThought, style = readingStyle, color = COUITheme.colorScheme.labelSecondary)
                    }
                }
                if (streaming) {
                    Text("正在生成", style = COUITheme.textStyles.description, color = COUITheme.colorScheme.primary)
                }
            }
            MessageContextMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                actionsEnabled = actionsEnabled,
                canRegenerate = !isScene && canRegenerate,
                canBranch = canBranch,
                onCopy = onCopy,
                onRegenerate = onRegenerate,
                onBranch = onBranch,
            )
        }
        return
    }

    if (isScene) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (!streaming) menuExpanded = true },
                        ),
                    color = COUITheme.colorScheme.chatMessageTint(),
                    shape = SquircleShape(COUIRoundCorner.Normal.S, COUIRoundCorner.Normal.Weight),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = verticalPadding)) {
                        Text(
                            text = "旁白${
                                item.speaker.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
                            }",
                            style = COUITheme.textStyles.description,
                            color = COUITheme.colorScheme.primaryText,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val narrationStyle = messageStyle.copy(fontStyle = FontStyle.Italic)
                        val narrationColor = COUITheme.colorScheme.labelPrimary
                        RoleplayMessageContent(
                            text = item.message,
                            modifier = Modifier.padding(top = 3.dp),
                            style = narrationStyle,
                            baseColor = narrationColor,
                            streaming = streaming,
                        ) { prose ->
                            ParentheticalMessageText(prose, style = narrationStyle, baseColor = narrationColor)
                        }
                    }
                }
                MessageContextMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    actionsEnabled = actionsEnabled,
                    canRegenerate = false,
                    canBranch = canBranch,
                    onCopy = onCopy,
                    onRegenerate = onRegenerate,
                    onBranch = onBranch,
                )
            }
        }
        return
    }

    if (isDirector) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (!streaming) menuExpanded = true },
                        ),
                    color = androidx.compose.ui.graphics.Color.Transparent,
                ) {
                    Column(
                        Modifier.padding(horizontal = 14.dp, vertical = verticalPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                COUIIcons.Play,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = COUITheme.colorScheme.labelSecondary,
                            )
                            Text(
                                text = "导演${
                                    item.speaker.takeIf(String::isNotBlank)?.let { " · $it" }
                                        .orEmpty()
                                }",
                                modifier = Modifier.padding(start = 5.dp),
                                style = COUITheme.textStyles.description,
                                color = COUITheme.colorScheme.labelSecondary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        ParentheticalMessageText(
                            text = item.message,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 3.dp),
                            style = messageStyle,
                            baseColor = COUITheme.colorScheme.labelPrimary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                MessageContextMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    actionsEnabled = actionsEnabled,
                    canRegenerate = false,
                    canBranch = canBranch,
                    onCopy = onCopy,
                    onRegenerate = onRegenerate,
                    onBranch = onBranch,
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Box(Modifier.size(40.dp)) {
                ChatPersonaAvatar(
                    bytes = avatarBytes[item.speaker],
                    name = item.speaker,
                    modifier = Modifier.size(40.dp),
                )
                EmotionBadge(
                    innerThought = item.innerThought,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Column {
            if (!isUser) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    Text(
                        text = item.speaker.ifBlank { "人物" },
                        style = COUITheme.textStyles.description,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                    if (onSpeak != null && !streaming) {
                        val isThisSpeaking = isSpeaking && speakingId == item.transcriptKey()
                        IconButton(
                            onClick = onSpeak,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(18.dp),
                        ) {
                            Icon(
                                imageVector = if (isThisSpeaking) COUIIcons.VolumeOff else COUIIcons.VolumeUp,
                                contentDescription = if (isThisSpeaking) "停止朗读" else "朗读",
                                modifier = Modifier.size(14.dp),
                                tint = if (isThisSpeaking) COUITheme.colorScheme.primary else COUITheme.colorScheme.labelSecondary.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
            Box {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 340.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (!streaming) menuExpanded = true },
                        ),
                    color = if (isUser) {
                        COUITheme.colorScheme.chatMessageTint()
                    } else {
                        COUITheme.colorScheme.surfaceWithCard
                    },
                    shape = RoundedCornerShape(
                        topStart = COUIRoundCorner.XL,
                        topEnd = COUIRoundCorner.XL,
                        bottomStart = if (isUser) COUIRoundCorner.XL else COUIRoundCorner.XS,
                        bottomEnd = if (isUser) COUIRoundCorner.XS else COUIRoundCorner.XL,
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = verticalPadding)) {
                        if (isUser) Text(
                            text = if (item.speaker == "作者") "作者" else "你 · ${item.speaker}",
                            style = COUITheme.textStyles.description,
                            color = COUITheme.colorScheme.labelSecondary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (isUser) {
                            ParentheticalMessageText(
                                text = item.message,
                                modifier = Modifier.padding(top = 3.dp),
                                style = messageStyle,
                                baseColor = COUITheme.colorScheme.labelPrimary,
                            )
                        } else {
                            val replyColor = COUITheme.colorScheme.labelPrimary
                            RoleplayMessageContent(
                                text = item.message,
                                modifier = Modifier.padding(top = 3.dp),
                                style = messageStyle,
                                baseColor = replyColor,
                                streaming = streaming,
                            ) { prose ->
                                ParentheticalMessageText(prose, style = messageStyle, baseColor = replyColor)
                            }
                        }
                        if (includeInnerThoughts && item.innerThought.isNotBlank() && !isUser) {
                            HorizontalDivider(
                                modifier = Modifier.padding(top = 6.dp),
                                color = COUITheme.colorScheme.divider,
                            )
                            Text(
                                text = "内心独白：${item.innerThought}",
                                modifier = Modifier.padding(top = 6.dp),
                                style = COUITheme.textStyles.description.copy(
                                    fontStyle = FontStyle.Italic,
                                ),
                                color = COUITheme.colorScheme.primaryText,
                            )
                        }
                        if (streaming) {
                            Text(
                                "正在生成",
                                modifier = Modifier.padding(top = 4.dp),
                                style = COUITheme.textStyles.description,
                                color = COUITheme.colorScheme.primary,
                            )
                        }
                    }
                }
                MessageContextMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    actionsEnabled = actionsEnabled,
                    canRegenerate = canRegenerate,
                    canBranch = canBranch,
                    onCopy = onCopy,
                    onRegenerate = onRegenerate,
                    onBranch = onBranch,
                )
            }
        }
    }
}

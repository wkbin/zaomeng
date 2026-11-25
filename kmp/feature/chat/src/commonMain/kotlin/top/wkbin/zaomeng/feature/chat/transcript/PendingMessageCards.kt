package top.wkbin.zaomeng.feature.chat

import top.wkbin.zaomeng.ui.theme.chatMessageTint

import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
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
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.ProgressIndicatorDefaults
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Play
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
internal fun PendingUserMessageBubble(
    pending: PendingUserMessage,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onReconcile: () -> Unit,
    onRecover: () -> Unit,
    requiresRecovery: Boolean,
) {
    if (pending.messageKind == "plot" || pending.messageKind == "fourth_wall") {
        PendingDirectorInstructionCard(
            pending = pending,
            onRetry = onRetry,
            onEdit = onEdit,
            onReconcile = onReconcile,
            onRecover = onRecover,
            requiresRecovery = requiresRecovery,
        )
        return
    }
    if (pending.messageKind == "narration") {
        PendingNarrationMessageCard(
            pending = pending,
            onRetry = onRetry,
            onEdit = onEdit,
            onReconcile = onReconcile,
            onRecover = onRecover,
            requiresRecovery = requiresRecovery,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 340.dp),
            color = COUITheme.colorScheme.chatMessageTint(),
            shape = RoundedCornerShape(
                topStart = COUIRoundCorner.XL,
                topEnd = COUIRoundCorner.XL,
                bottomStart = COUIRoundCorner.XL,
                bottomEnd = COUIRoundCorner.XS,
            ),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(
                    pending.speakerOverride.takeIf(String::isNotBlank)?.let { "$it · 代发" } ?: "你",
                    style = COUITheme.textStyles.description,
                    color = COUITheme.colorScheme.labelSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                ParentheticalMessageText(
                    text = pending.message,
                    modifier = Modifier.padding(top = 3.dp),
                    style = COUITheme.textStyles.bodyM,
                    baseColor = COUITheme.colorScheme.labelPrimary,
                )
                when (pending.status) {
                    PendingUserMessageStatus.Sending -> Row(
                        modifier = Modifier.padding(top = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            size = 14.dp,
                            strokeWidth = 2.dp,
                            colors = ProgressIndicatorDefaults.loadingViewColors(
                                foregroundColor = COUITheme.colorScheme.primaryText,
                                backgroundColor = Color.Transparent,
                            ),
                        )
                        Text(
                            pending.statusText.ifBlank { "正在发送" },
                            modifier = Modifier.padding(start = 7.dp),
                            style = COUITheme.textStyles.description,
                            color = COUITheme.colorScheme.primaryText.copy(alpha = 0.78f),
                        )
                    }

                    PendingUserMessageStatus.Failed -> {
                        Text(
                            pending.statusText.ifBlank { "发送失败" },
                            modifier = Modifier.padding(top = 7.dp),
                            style = COUITheme.textStyles.description,
                            color = COUITheme.colorScheme.error,
                        )
                        if (requiresRecovery) {
                            TextButton(
                                text = "恢复会话",
                                onClick = onRecover,
                                modifier = Modifier.align(Alignment.End),
                                minWidth = 0.dp,
                                textStyle = COUITheme.textStyles.bodyM,
                            )
                        } else {
                            Row(
                                modifier = Modifier.align(Alignment.End),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    text = "编辑",
                                    onClick = onEdit,
                                    minWidth = 0.dp,
                                    textStyle = COUITheme.textStyles.bodyM,
                                )
                                if (pending.retryable) {
                                    TextButton(
                                        text = "重试",
                                        onClick = onRetry,
                                        minWidth = 0.dp,
                                        textStyle = COUITheme.textStyles.bodyM,
                                    )
                                }
                            }
                        }
                    }

                    PendingUserMessageStatus.OutcomeUnknown -> {
                        Text(
                            pending.statusText.ifBlank { "连接中断，正在核对结果" },
                            modifier = Modifier.padding(top = 7.dp),
                            style = COUITheme.textStyles.description,
                            color = COUITheme.colorScheme.error,
                        )
                        TextButton(
                            text = if (requiresRecovery) "恢复会话" else "核对结果",
                            onClick = if (requiresRecovery) onRecover else onReconcile,
                            modifier = Modifier.align(Alignment.End),
                            minWidth = 0.dp,
                            textStyle = COUITheme.textStyles.bodyM,
                        )
                    }
                }
            }
        }
    }
}
@Composable
internal fun PendingNarrationMessageCard(
    pending: PendingUserMessage,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onReconcile: () -> Unit,
    onRecover: () -> Unit,
    requiresRecovery: Boolean,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            color = COUITheme.colorScheme.chatMessageTint(),
            shape = SquircleShape(COUIRoundCorner.Normal.S, COUIRoundCorner.Normal.Weight),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(
                    "旁白",
                    style = COUITheme.textStyles.description,
                    color = COUITheme.colorScheme.primaryText,
                    fontWeight = FontWeight.SemiBold,
                )
                ParentheticalMessageText(
                    text = pending.message,
                    modifier = Modifier.padding(top = 3.dp),
                    style = COUITheme.textStyles.bodyM.copy(fontStyle = FontStyle.Italic),
                    baseColor = COUITheme.colorScheme.labelPrimary,
                )
                when (pending.status) {
                    PendingUserMessageStatus.Sending -> Row(
                        modifier = Modifier.padding(top = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            size = 14.dp,
                            strokeWidth = 2.dp,
                            colors = ProgressIndicatorDefaults.loadingViewColors(
                                foregroundColor = COUITheme.colorScheme.primaryText,
                                backgroundColor = Color.Transparent,
                            ),
                        )
                        Text(
                            pending.statusText.ifBlank { "正在发送旁白" },
                            modifier = Modifier.padding(start = 7.dp),
                            style = COUITheme.textStyles.description,
                            color = COUITheme.colorScheme.primaryText.copy(alpha = 0.78f),
                        )
                    }

                    PendingUserMessageStatus.Failed -> {
                        Text(
                            pending.statusText.ifBlank { "旁白发送失败" },
                            modifier = Modifier.padding(top = 7.dp),
                            style = COUITheme.textStyles.description,
                            color = COUITheme.colorScheme.error,
                        )
                        if (requiresRecovery) {
                            TextButton(
                                text = "恢复会话",
                                onClick = onRecover,
                                modifier = Modifier.align(Alignment.End),
                                minWidth = 0.dp,
                                textStyle = COUITheme.textStyles.bodyM,
                            )
                        } else {
                            Row(modifier = Modifier.align(Alignment.End)) {
                                TextButton(
                                    text = "编辑",
                                    onClick = onEdit,
                                    minWidth = 0.dp,
                                    textStyle = COUITheme.textStyles.bodyM,
                                )
                                if (pending.retryable) {
                                    TextButton(
                                        text = "重试",
                                        onClick = onRetry,
                                        minWidth = 0.dp,
                                        textStyle = COUITheme.textStyles.bodyM,
                                    )
                                }
                            }
                        }
                    }

                    PendingUserMessageStatus.OutcomeUnknown -> {
                        Text(
                            pending.statusText.ifBlank { "连接中断，正在核对结果" },
                            modifier = Modifier.padding(top = 7.dp),
                            style = COUITheme.textStyles.description,
                            color = COUITheme.colorScheme.error,
                        )
                        TextButton(
                            text = if (requiresRecovery) "恢复会话" else "核对结果",
                            onClick = if (requiresRecovery) onRecover else onReconcile,
                            modifier = Modifier.align(Alignment.End),
                            minWidth = 0.dp,
                            textStyle = COUITheme.textStyles.bodyM,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PendingDirectorInstructionCard(
    pending: PendingUserMessage,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onReconcile: () -> Unit,
    onRecover: () -> Unit,
    requiresRecovery: Boolean,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            color = androidx.compose.ui.graphics.Color.Transparent,
        ) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
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
                        if (pending.messageKind == "fourth_wall") "作者指令" else "导演指令",
                        modifier = Modifier.padding(start = 5.dp),
                        style = COUITheme.textStyles.description,
                        color = COUITheme.colorScheme.labelSecondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                ParentheticalMessageText(
                    text = pending.message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    style = COUITheme.textStyles.bodyM,
                    baseColor = COUITheme.colorScheme.labelPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                when (pending.status) {
                    PendingUserMessageStatus.Sending -> Row(
                        modifier = Modifier.padding(top = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            size = 14.dp,
                            strokeWidth = 2.dp,
                            colors = ProgressIndicatorDefaults.loadingViewColors(
                                foregroundColor = COUITheme.colorScheme.labelSecondary,
                                backgroundColor = Color.Transparent,
                            ),
                        )
                        Text(
                            pending.statusText.ifBlank { "正在安排下一拍" },
                            modifier = Modifier.padding(start = 7.dp),
                            style = COUITheme.textStyles.description,
                            color = COUITheme.colorScheme.labelSecondary,
                        )
                    }

                    PendingUserMessageStatus.Failed -> {
                        Text(
                            pending.statusText.ifBlank { "指令未生效" },
                            modifier = Modifier.padding(top = 7.dp),
                            style = COUITheme.textStyles.description,
                            color = COUITheme.colorScheme.error,
                        )
                        if (requiresRecovery) {
                            TextButton(
                                text = "恢复会话",
                                onClick = onRecover,
                                modifier = Modifier.align(Alignment.End),
                                minWidth = 0.dp,
                                textStyle = COUITheme.textStyles.bodyM,
                            )
                        } else {
                            Row(modifier = Modifier.align(Alignment.End)) {
                                TextButton(
                                    text = "编辑",
                                    onClick = onEdit,
                                    minWidth = 0.dp,
                                    textStyle = COUITheme.textStyles.bodyM,
                                )
                                if (pending.retryable) {
                                    TextButton(
                                        text = "重试",
                                        onClick = onRetry,
                                        minWidth = 0.dp,
                                        textStyle = COUITheme.textStyles.bodyM,
                                    )
                                }
                            }
                        }
                    }

                    PendingUserMessageStatus.OutcomeUnknown -> {
                        Text(
                            pending.statusText.ifBlank { "连接中断，正在核对结果" },
                            modifier = Modifier.padding(top = 7.dp),
                            style = COUITheme.textStyles.description,
                            color = COUITheme.colorScheme.error,
                        )
                        TextButton(
                            text = if (requiresRecovery) "恢复会话" else "核对结果",
                            onClick = if (requiresRecovery) onRecover else onReconcile,
                            modifier = Modifier.align(Alignment.End),
                            minWidth = 0.dp,
                            textStyle = COUITheme.textStyles.bodyM,
                        )
                    }
                }
            }
        }
    }
}

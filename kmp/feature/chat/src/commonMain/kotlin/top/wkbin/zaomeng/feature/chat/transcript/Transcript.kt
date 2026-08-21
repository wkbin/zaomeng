package top.wkbin.zaomeng.feature.chat

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
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
import androidx.window.core.layout.WindowSizeClass
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
import top.wkbin.zaomeng.platform.rememberPlatformTts
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun Transcript(
    session: DialogueSessionDto,
    avatarBytes: Map<String, ByteArray>,
    sending: Boolean,
    loadingEarlier: Boolean,
    hasMoreHistory: Boolean,
    onLoadEarlier: () -> Unit,
    modelReasoning: String,
    streamingReplies: List<StreamingReplyPart>,
    pendingUserMessage: PendingUserMessage?,
    displayPreferences: ChatDisplayPreferences,
    actionsEnabled: Boolean,
    includeInnerThoughts: Boolean,
    onRegenerate: () -> Unit,
    onBranch: (String) -> Unit,
    onPendingRetry: () -> Unit,
    onPendingEdit: () -> Unit,
    onPendingReconcile: () -> Unit,
    onPendingRecover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val clipboardWriter = rememberClipboardTextWriter()
    val tts = rememberPlatformTts()
    val isSpeaking by tts.isSpeaking.collectAsStateWithLifecycle()
    val speakingId by tts.currentSpeakingId.collectAsStateWithLifecycle()
    val bottomThresholdPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    val transcript = session.transcript
    val latestAssistantIndex = transcript.indexOfLast { item ->
        item.role != "user" && item.role != "scene" && item.role != "director"
    }
    val latestUserIndex = transcript.indexOfLast { item -> item.role == "user" }
    var followNewMessages by remember(session.sessionId) { mutableStateOf(true) }
    var unseenMessages by remember(session.sessionId) { mutableIntStateOf(0) }
    var previousVisibleCount by remember(session.sessionId) {
        mutableIntStateOf(
            transcript.size + streamingReplies.size +
                    (if (modelReasoning.isBlank()) 0 else 1) +
                    if (pendingUserMessage == null) 0 else 1,
        )
    }
    var previousFirstKey by remember(session.sessionId) { mutableStateOf("") }
    val isAtBottom by remember(listState, bottomThresholdPx) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
            layout.totalItemsCount == 0 || (
                    lastVisible?.index == layout.totalItemsCount - 1 &&
                            lastVisible.offset + lastVisible.size <=
                            layout.viewportEndOffset + bottomThresholdPx
                    )
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to isAtBottom }
            .distinctUntilChanged()
            .collect { (scrolling, atBottom) ->
                if (scrolling) followNewMessages = atBottom
                if (atBottom) unseenMessages = 0
            }
    }
    // 滚动到顶部时自动加载更早消息（仅在主动滚动时触发，避免加载历史后连环拉取）
    val latestHasMoreHistory by rememberUpdatedState(hasMoreHistory)
    val latestLoadingEarlier by rememberUpdatedState(loadingEarlier)
    val latestOnLoadEarlier by rememberUpdatedState(onLoadEarlier)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { (firstIndex, scrolling) ->
                if (scrolling && firstIndex <= 2 && latestHasMoreHistory && !latestLoadingEarlier) {
                    latestOnLoadEarlier()
                }
            }
    }

    val visibleCount = transcript.size + streamingReplies.size +
            (if (sending && modelReasoning.isNotBlank()) 1 else 0) +
            if (pendingUserMessage == null) 0 else 1
    val lastContentIndex = (visibleCount - 1).coerceAtLeast(0)
    val contentRevision = (transcript.lastOrNull()?.message?.hashCode() ?: 0) * 31 +
            streamingReplies.sumOf { it.text.length + it.innerThought.length } * 17 +
            modelReasoning.length * 13 +
            (pendingUserMessage?.let { 31 * it.status.hashCode() + it.statusText.hashCode() } ?: 0)
    val firstKey = transcript.firstOrNull()?.transcriptKey().orEmpty()
    LaunchedEffect(visibleCount, contentRevision, sending, firstKey) {
        // 向上加载历史会整体前插：首条 key 变化且数量增加视为 prepend，不纳入“新消息”计数
        val prepended = firstKey.isNotBlank() && firstKey != previousFirstKey &&
            transcript.size != previousVisibleCount
        val added = if (prepended) {
            0
        } else {
            (visibleCount - previousVisibleCount).coerceAtLeast(0)
        }
        previousVisibleCount = visibleCount
        previousFirstKey = firstKey
        if (followNewMessages) {
            listState.scrollToBottom(lastContentIndex, animated = false)
            unseenMessages = 0
        } else if (added > 0) {
            unseenMessages = (unseenMessages + added).coerceAtMost(99)
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = 16.dp,
                vertical = if (displayPreferences.compactMode) 6.dp else 10.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(
                if (displayPreferences.compactMode) 3.dp else 8.dp,
            ),
        ) {
            if (hasMoreHistory || loadingEarlier) {
                item(key = "load-earlier") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (loadingEarlier) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "正在加载更早的消息…",
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            TextButton(onClick = onLoadEarlier) {
                                Text("加载更早的消息")
                            }
                        }
                    }
                }
            }

            if (
                transcript.isEmpty() && streamingReplies.isEmpty() &&
                modelReasoning.isBlank() && pendingUserMessage == null
            ) {
                item {
                    Text(
                        "这一幕还没有留下台词。写下第一句话，让故事继续。",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            itemsIndexed(
                items = transcript,
                key = { _, item -> item.transcriptKey() },
            ) { index, item ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TranscriptBubble(
                        item = item,
                        avatarBytes = avatarBytes,
                        displayPreferences = displayPreferences,
                        actionsEnabled = actionsEnabled,
                        includeInnerThoughts = includeInnerThoughts,
                        canRegenerate = index == latestAssistantIndex && !sending,
                        isSpeaking = isSpeaking,
                        speakingId = speakingId,
                        onSpeak = {
                            val key = item.transcriptKey()
                            if (isSpeaking && speakingId == key) {
                                tts.stop()
                            } else {
                                tts.speak(
                                    id = key,
                                    text = item.message,
                                )
                            }
                        },
                        onCopy = {
                            scrollScope.launch {
                                clipboardWriter(item.message)
                            }
                        },
                        onRegenerate = onRegenerate,
                        onBranch = { onBranch(item.turnId) },
                    )
                    if (!sending && index == latestUserIndex && modelReasoning.isNotBlank()) {
                        ModelReasoningBlock(modelReasoning, streaming = false)
                    }
                }
            }

            pendingUserMessage?.let { pending ->
                item(key = "pending-${pending.operationId}") {
                    PendingUserMessageBubble(
                        pending = pending,
                        onRetry = onPendingRetry,
                        onEdit = onPendingEdit,
                        onReconcile = onPendingReconcile,
                        onRecover = onPendingRecover,
                        requiresRecovery = session.status != "ready",
                    )
                }
            }

            if (sending && modelReasoning.isNotBlank()) {
                item(key = "model-reasoning") {
                    ModelReasoningBlock(
                        modelReasoning,
                        streaming = streamingReplies.none { it.text.isNotBlank() },
                    )
                }
            }

            items(
                items = streamingReplies,
                key = { "stream-${it.index}" },
            ) { item ->
                TranscriptBubble(
                    item = TranscriptItemDto(
                        speaker = item.speaker.ifBlank { "生成中" },
                        message = item.text,
                        innerThought = item.innerThought,
                        role = item.role,
                    ),
                    avatarBytes = avatarBytes,
                    displayPreferences = displayPreferences,
                    actionsEnabled = false,
                    includeInnerThoughts = includeInnerThoughts,
                    streaming = true,
                    onCopy = {},
                    onRegenerate = {},
                    onBranch = {},
                )
            }

        }

        if (!isAtBottom || unseenMessages > 0) {
            ExtendedFloatingActionButton(
                onClick = {
                    followNewMessages = true
                    unseenMessages = 0
                    scrollScope.launch {
                        listState.scrollToBottom(lastContentIndex, animated = true)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                icon = {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                },
                text = {
                    Text(
                        if (unseenMessages > 0) "$unseenMessages 条新消息" else "回到底部",
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}
internal suspend fun LazyListState.scrollToBottom(itemIndex: Int, animated: Boolean) {
    val targetIndex = itemIndex.coerceAtLeast(0)
    var target = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
    if (target == null) {
        if (animated) {
            animateScrollToItem(targetIndex)
        } else {
            scrollToItem(targetIndex)
        }
        target = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
    }
    val overflow = target?.let {
        it.offset + it.size - layoutInfo.viewportEndOffset
    } ?: return
    if (overflow > 0) {
        if (animated) {
            animateScrollBy(overflow.toFloat())
        } else {
            scrollBy(overflow.toFloat())
        }
    }
}

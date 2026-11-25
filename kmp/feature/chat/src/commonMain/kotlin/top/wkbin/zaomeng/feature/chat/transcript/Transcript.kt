package top.wkbin.zaomeng.feature.chat

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
import io.github.suqi8.coui.kmp.basic.Badge
import io.github.suqi8.coui.kmp.basic.BadgeBox
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.EmptyStateSizeType
import io.github.suqi8.coui.kmp.basic.FloatingActionButton
import io.github.suqi8.coui.kmp.basic.FloatingActionButtonDefaults
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ExpandMore
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
import top.wkbin.zaomeng.data.api.ImportedSwipePageDto
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
    readingLayout: Boolean,
    actionsEnabled: Boolean,
    includeInnerThoughts: Boolean,
    onRegenerate: () -> Unit,
    onBranch: (String) -> Unit,
    onPendingRetry: () -> Unit,
    onPendingEdit: () -> Unit,
    onPendingReconcile: () -> Unit,
    onPendingRecover: () -> Unit,
    onLoadImportedSwipes: suspend (messageIndex: String, offset: Int) -> ImportedSwipePageDto,
    onBranchImportedSwipe: (messageIndex: String, swipeIndex: Int) -> Unit,
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
    var swipeMessageIndex by rememberSaveable(session.runId, session.sessionId) { mutableStateOf<String?>(null) }
    swipeMessageIndex?.let { index ->
        ImportedSwipesDialog(
            messageIndex = index,
            load = onLoadImportedSwipes,
            onBranch = if (actionsEnabled && transcript.any { it.sillyTavernMessageIndex == index && it.role == "character" }) {
                { swipeIndex ->
                    swipeMessageIndex = null
                    onBranchImportedSwipe(index, swipeIndex)
                }
            } else null,
            onDismiss = { swipeMessageIndex = null },
        )
    }
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
    val onlyOpening = readingLayout && session.cardGreetingIndex != null && session.transcriptCount <= 1 &&
        transcript.size == 1 && visibleCount == 1 && !sending
    LaunchedEffect(visibleCount, contentRevision, sending, firstKey, onlyOpening) {
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
        if (followNewMessages && !onlyOpening) {
            listState.scrollToBottom(lastContentIndex, animated = false)
            unseenMessages = 0
        } else if (added > 0) {
            unseenMessages = (unseenMessages + added).coerceAtMost(99)
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.align(Alignment.TopCenter)
                .widthIn(max = if (readingLayout) 720.dp else 1000.dp).fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = 16.dp,
                vertical = if (displayPreferences.compactMode) 6.dp else 10.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(
                when {
                    displayPreferences.compactMode -> 3.dp
                    readingLayout -> 16.dp
                    else -> 8.dp
                },
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
                            CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                            Text(
                                "正在加载更早的消息…",
                                modifier = Modifier.padding(start = 8.dp),
                                style = COUITheme.textStyles.description,
                                color = COUITheme.colorScheme.labelSecondary,
                            )
                        } else {
                            TextButton(
                                text = "加载更早的消息",
                                onClick = onLoadEarlier,
                                minWidth = 0.dp,
                                textStyle = COUITheme.textStyles.bodyM,
                            )
                        }
                    }
                }
            }

            if (
                transcript.isEmpty() && streamingReplies.isEmpty() &&
                modelReasoning.isBlank() && pendingUserMessage == null
            ) {
                item {
                    EmptyState(
                        title = "这一幕还没有留下台词。",
                        subtitle = "写下第一句话，让故事继续。",
                        sizeType = EmptyStateSizeType.Small,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
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
                        readingLayout = readingLayout,
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
                    if (item.worldBookMatches.isNotEmpty()) {
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        TextButton(
                            text = if (expanded) "收起世界书" else "本轮注入 ${item.worldBookMatches.size} 条世界书设定",
                            onClick = { expanded = !expanded },
                            minWidth = 0.dp,
                            textStyle = COUITheme.textStyles.bodyM,
                        )
                        if (expanded) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item.worldBookMatches.forEach { match ->
                                    Column {
                                        val source = if (match.source == "character_book") "卡内世界书" else "独立世界书"
                                        val trigger = when {
                                            match.forcedActivation -> "正文指令强制触发"
                                            match.stickyActive -> "粘滞"
                                            else -> match.keywords.joinToString("、").ifBlank { "常驻" }
                                        }
                                        Text("${match.title} · $source · $trigger", style = COUITheme.textStyles.buttonM)
                                        if (match.recursionDepth > 0) {
                                            Text("递归触发 · 第 ${match.recursionDepth} 层", style = COUITheme.textStyles.bodyXS)
                                        }
                                        Text(match.excerpt, style = COUITheme.textStyles.bodyXS)
                                    }
                                }
                            }
                        }
                    }
                    val importedIndex = item.sillyTavernMessageIndex
                    if (importedIndex != null && item.sillyTavernSwipeCount != 0) {
                        TextButton(
                            text = item.sillyTavernSwipeCount?.let { "查看 $it 个回复版本" } ?: "查看迁移的备用回复",
                            onClick = { swipeMessageIndex = importedIndex },
                            minWidth = 0.dp,
                            textStyle = COUITheme.textStyles.bodyM,
                        )
                    }
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
                    readingLayout = readingLayout,
                    streaming = true,
                    onCopy = {},
                    onRegenerate = {},
                    onBranch = {},
                )
            }

        }

        BadgeBox(
            modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
            badge = { Badge(count = unseenMessages, visible = unseenMessages > 0, stroke = true) },
        ) {
            FloatingActionButton(
                visible = !isAtBottom || unseenMessages > 0,
                onClick = {
                    followNewMessages = true
                    unseenMessages = 0
                    scrollScope.launch {
                        listState.scrollToBottom(lastContentIndex, animated = true)
                    }
                },
                containerColor = FloatingActionButtonDefaults.secondaryContainerColor(),
                contentColor = COUITheme.colorScheme.labelPrimary,
                shadowElevation = 0.dp,
                minWidth = FloatingActionButtonDefaults.TinySize,
                minHeight = FloatingActionButtonDefaults.TinySize,
            ) {
                Icon(
                    COUIIcons.ExpandMore,
                    contentDescription = if (unseenMessages > 0) {
                        "回到底部，$unseenMessages 条新消息"
                    } else {
                        "回到底部"
                    },
                )
            }
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

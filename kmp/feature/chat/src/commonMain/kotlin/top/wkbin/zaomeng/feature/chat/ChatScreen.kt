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
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

internal data class MessageKindOption(
    val value: String,
    val label: String,
)

internal val messageKindOptions = listOf(
    MessageKindOption("dialogue", "对话"),
    MessageKindOption("narration", "旁白"),
    MessageKindOption("plot", "导演"),
    MessageKindOption("fourth_wall", "第四面墙"),
)

private data class DirectorActionOption(val value: String, val label: String)

private val directorActionOptions = listOf(
    DirectorActionOption("advance", "推进剧情"),
    DirectorActionOption("slow_emotion", "放慢情绪"),
    DirectorActionOption("conflict", "加强冲突"),
    DirectorActionOption("viewpoint", "切换视角"),
    DirectorActionOption("fourth_wall", "第四面墙"),
)

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    runId: String,
    sessionId: String,
    onBack: () -> Unit,
    onOpenBranch: (runId: String, sessionId: String) -> Unit,
    onOpenStoryRecap: () -> Unit,
    onSelectSession: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val wideLayout = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var directorOpen by rememberSaveable { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = {
        searchOpen = false
        viewModel.updateSearchQuery("")
        keyboardController?.hide()
    }

    PlatformBackHandler(enabled = searchOpen) {
        closeSearch()
    }

    LaunchedEffect(runId, sessionId) {
        viewModel.load(runId, sessionId, force = true)
    }
    DisposableEffect(lifecycleOwner, runId, sessionId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.refreshPluginActions()
                Lifecycle.Event.ON_STOP -> viewModel.pauseContinuousObserve()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.pauseContinuousObserve()
        }
    }
    LaunchedEffect(state.error) {
        if (state.error.isNotBlank()) {
            snackbarHostState.showSnackbar(state.error)
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.notice) {
        if (state.notice.isNotBlank()) {
            snackbarHostState.showSnackbar(state.notice)
            viewModel.clearNotice()
        }
    }
    LaunchedEffect(state.navigationSession?.sessionId) {
        state.navigationSession?.let { session ->
            toolsOpen = false
            viewModel.consumeNavigationSession()
            onOpenBranch(session.runId, session.sessionId)
        }
    }
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    if (toolsOpen && state.session != null) {
        ChatToolsSheet(
            state = state,
            onDismiss = { toolsOpen = false },
            onDirector = viewModel::requestDirectorOptions,
            onCorrectLatest = viewModel::correctLatest,
            onDeepReviewLatest = viewModel::deepReviewLatest,
            onBranchTurn = viewModel::branchFromTurn,
            onBranchScene = viewModel::branchFromScene,
            onUpdateBranchMeta = viewModel::updateBranchMeta,
            onToggleMainlineEvent = viewModel::setMainlineEventLocked,
            onOpenExistingBranch = { branchSessionId ->
                toolsOpen = false
                onOpenBranch(runId, branchSessionId)
            },
            onLoadScenes = viewModel::loadSceneCards,
            onRecommendScene = viewModel::recommendNextScene,
            onSwitchScene = viewModel::switchScene,
            onSaveMemory = viewModel::saveMemory,
            onDeleteMemory = viewModel::deleteMemory,
            onUpdateAutomaticMemoryStatus = viewModel::updateAutomaticMemoryStatus,
            onMergeDuplicateMemories = viewModel::mergeDuplicateMemories,
            onRelationLock = viewModel::setRelationLock,
            onOpenStoryRecap = onOpenStoryRecap,
        )
    }

    if (state.toolOptions.isNotEmpty()) {
        ChatToolOptionsDialog(
            title = state.toolOptionsTitle,
            options = state.toolOptions,
            enabled = state.canUseTools,
            onChoose = viewModel::chooseToolOption,
            onDismiss = viewModel::dismissToolOptions,
        )
    }

    if (directorOpen) {
        DirectorDialog(
            enabled = state.canUseTools,
            onDismiss = { directorOpen = false },
            onGenerate = { goal, action ->
                directorOpen = false
                viewModel.requestDirectorOptions(goal, action)
            },
        )
    }

    val hasChatBackground = state.chatDisplay.backgroundImageUri.isNotBlank()
    val chromeMaskColor = MaterialTheme.colorScheme.surface.copy(
        alpha = if (hasChatBackground) 0.82f else 1f,
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        ChatBackgroundImage(
            imageUri = state.chatDisplay.backgroundImageUri,
            opacity = state.chatDisplay.backgroundOpacity,
            blurRadius = state.chatDisplay.backgroundBlurRadius,
        )
        Row(Modifier.fillMaxSize()) {
            if (wideLayout) {
                ChatSessionPane(
                    sessions = state.runSessions,
                    activeSessionId = sessionId,
                    onSelect = onSelectSession,
                )
            }
            Box(Modifier.weight(1f)) {
                Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Surface(color = chromeMaskColor) {
                if (searchOpen) {
                    ChatSearchTopBar(
                        query = state.searchQuery,
                        searchFocusRequester = searchFocusRequester,
                        onQueryChange = viewModel::updateSearchQuery,
                        onClose = closeSearch,
                    )
                } else {
                    ChatTopBar(
                        session = state.session,
                        refreshing = state.refreshing,
                        refreshEnabled = state.canRefresh,
                        toolsEnabled = state.canUseTools,
                        onBack = onBack,
                        onRefresh = viewModel::refresh,
                        onOpenTools = { toolsOpen = true },
                        onOpenSearch = { searchOpen = true },
                    )
                }
            }
        },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.session != null) {
                Surface(color = chromeMaskColor) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.widthIn(max = 1000.dp).fillMaxWidth()) {
                            ChatComposer(
                                state = state,
                                avatarBytes = state.avatarBytes,
                                onDraftChange = viewModel::updateDraft,
                                onClearSpeakerOverride = viewModel::clearDraftSpeakerOverride,
                                onMessageKindChange = viewModel::selectMessageKind,
                                onPacingChange = viewModel::selectPacing,
                                onInvokePluginAction = viewModel::invokePluginAction,
                                onOpenDirector = { directorOpen = true },
                                onSend = viewModel::send,
                                onToggleContinuousObserve = viewModel::toggleContinuousObserve,
                                onToggleGenerationEnhancer = viewModel::toggleGenerationEnhancer,
                                onRecover = viewModel::recoverPending,
                                onReconcile = viewModel::reconcileUnknownSend,
                                onRetry = viewModel::retryLastSend,
                                onDiscardRetry = viewModel::discardFailedSend,
                            )
                        }
                    }
                }
            }
        },
        ) { innerPadding ->
            when {
                state.loading -> ChatLoading(Modifier.padding(innerPadding))
                state.session == null -> MissingChat(
                    error = state.error,
                    onRetry = viewModel::refresh,
                    modifier = Modifier.padding(innerPadding),
                )

                else -> Box(
                    Modifier
                        .padding(innerPadding)
                        .fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        Modifier
                            .fillMaxHeight()
                            .widthIn(max = 1000.dp)
                            .fillMaxWidth(),
                    ) {
                        if (searchOpen && state.searchQuery.isNotBlank()) {
                            ChatSearchResults(
                                query = state.searchQuery,
                                searching = state.searching,
                                results = state.searchResults,
                                actionsEnabled = state.canUseTools,
                                onBranch = viewModel::branchFromTurn,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            val session = requireNotNull(state.session)
                            val hasConsistencyIssue = session.consistencyMonitor.consistencyInsight()
                                ?.issueCount
                                ?.let { it > 0 } == true
                            if (hasConsistencyIssue) {
                                ChatContextStrip(
                                    session = session,
                                    onOpenTools = { toolsOpen = true },
                                )
                            }
                            Transcript(
                                session = session,
                                avatarBytes = state.avatarBytes,
                                sending = state.sending,
                                modelReasoning = state.modelReasoning.takeIf {
                                    state.chatDisplay.showModelReasoning
                                }.orEmpty(),
                                streamingReplies = state.streamingReplies,
                                pendingUserMessage = state.pendingUserMessage,
                                displayPreferences = state.chatDisplay,
                                actionsEnabled = state.canUseTools,
                                includeInnerThoughts = state.includeInnerThoughts,
                                onRegenerate = viewModel::correctLatest,
                                onBranch = viewModel::branchFromTurn,
                                onPendingRetry = viewModel::retryLastSend,
                                onPendingEdit = viewModel::discardFailedSend,
                                onPendingReconcile = viewModel::reconcileUnknownSend,
                                onPendingRecover = viewModel::recoverPending,
                                loadingEarlier = state.loadingEarlier,
                                hasMoreHistory = session.transcriptCount > session.transcript.size,
                                onLoadEarlier = viewModel::loadEarlierMessages,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
            }
        }
    }
}

/** 桌面端聊天主从布局左侧面板：本卷会话列表，点击切换到其他会话。 */
@Composable
private fun ChatContextStrip(session: DialogueSessionDto, onOpenTools: () -> Unit) {
    val summary = remember(session) { chatContextSummary(session) }
    val consistencyIssueCount = session.consistencyMonitor.consistencyInsight()
        ?.issueCount
        ?.takeIf { it > 0 }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenTools),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "${summary.mode} · ${summary.scene}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    summary.participants,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                consistencyIssueCount?.let { count ->
                    Text(
                        "$count 项一致性提醒，点击查看工具与详情",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                "工具",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun DirectorDialog(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onGenerate: (goal: String, action: String) -> Unit,
) {
    var goal by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf("advance") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (action == "fourth_wall") "第四面墙" else "剧情导演") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                directorActionOptions.chunked(2).forEach { actions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        actions.forEach { option ->
                            FilterChip(
                                selected = action == option.value,
                                onClick = { action = option.value },
                                label = { Text(option.label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (action == "fourth_wall") "以作者身份对角色下什么指令" else "这一幕希望怎样发展") },
                    placeholder = { Text(if (action == "fourth_wall") "例如：让他们和好，否则我删掉这段记忆" else "例如：让两人因为旧事发生正面冲突") },
                    minLines = 3,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (enabled && goal.isNotBlank()) onGenerate(goal, action)
                        },
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onGenerate(goal, action) },
                enabled = enabled && goal.isNotBlank(),
            ) { Text("生成方案") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    session: DialogueSessionDto?,
    refreshing: Boolean,
    refreshEnabled: Boolean,
    toolsEnabled: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        title = {
            Column {
                Text(
                    text = session?.participants?.joinToString("、")?.ifBlank { "人物会话" }
                        ?: "人物会话",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                session?.let {
                    Text(
                        text = "${it.mode.chineseMode()} · ${if (it.status == "ready") "可继续" else "待处理"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Default.Search, contentDescription = "搜索聊天记录")
            }
            IconButton(onClick = onOpenTools, enabled = toolsEnabled) {
                Icon(Icons.Default.MoreVert, contentDescription = "会话工具")
            }
            IconButton(onClick = onRefresh, enabled = refreshEnabled) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新聊天")
                }
            }
        },
    )
}

@Composable
private fun ChatToolOptionsDialog(
    title: String,
    options: List<ChatToolOption>,
    enabled: Boolean,
    onChoose: (ChatToolOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title.ifBlank { "选择一个方案" }) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(options, key = { "${it.label}-${it.value}" }) { option ->
                    OutlinedButton(
                        onClick = { onChoose(option) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(option.label, fontWeight = FontWeight.SemiBold)
                            if (
                                option.pluginSelection.isBlank() &&
                                option.value.isNotBlank() &&
                                option.value != option.label
                            ) {
                                Text(
                                    option.value,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (option.description.isNotBlank() && option.description != option.value) {
                                Text(
                                    option.description,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ChatLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                "正在打开这段故事…",
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MissingChat(error: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("暂时无法打开会话", style = MaterialTheme.typography.titleMedium)
            if (error.isNotBlank()) {
                Text(
                    error,
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("重试") }
        }
    }
}

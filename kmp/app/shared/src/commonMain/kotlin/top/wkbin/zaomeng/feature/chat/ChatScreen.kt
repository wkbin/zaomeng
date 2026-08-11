package top.wkbin.zaomeng.feature.chat

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

private data class MessageKindOption(
    val value: String,
    val label: String,
)

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

private val messageKindOptions = listOf(
    MessageKindOption("dialogue", "对话"),
    MessageKindOption("narration", "旁白"),
    MessageKindOption("plot", "导演"),
)

private data class DirectorActionOption(val value: String, val label: String)

private val directorActionOptions = listOf(
    DirectorActionOption("advance", "推进剧情"),
    DirectorActionOption("slow_emotion", "放慢情绪"),
    DirectorActionOption("conflict", "加强冲突"),
    DirectorActionOption("viewpoint", "切换视角"),
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
                                onMessageKindChange = viewModel::selectMessageKind,
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
private fun ChatSessionPane(
    sessions: List<DialogueSessionDto>,
    activeSessionId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sorted = remember(sessions) {
        sessions.sortedByDescending { it.updatedAt }
    }
    Surface(
        modifier = modifier.width(280.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = "本卷会话",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (sorted.isEmpty()) {
                Text(
                    text = "暂无会话",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(sorted, key = DialogueSessionDto::sessionId) { session ->
                        val selected = session.sessionId == activeSessionId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !selected) { onSelect(session.sessionId) }
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = session.title.ifBlank {
                                        session.lastEntryPreview.trim().ifBlank { "未命名会话" }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${chatSessionModeLabel(session.mode)} · ${session.updatedAt.toLocalDateTimeDisplay()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = if (session.status == "ready") "可继续" else "待处理",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (session.status == "ready") {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun chatSessionModeLabel(mode: String): String = when (mode) {
    "act" -> "扮演"
    "insert" -> "入场"
    else -> "旁观"
}

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
        title = { Text("剧情导演") },
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
                    label = { Text("这一幕希望怎样发展") },
                    placeholder = { Text("例如：让两人因为旧事发生正面冲突") },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatSearchTopBar(
    query: String,
    searchFocusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                modifier = Modifier.focusRequester(searchFocusRequester),
                placeholder = { Text("搜索台词、动作或人物") },
                leadingIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "关闭搜索")
                    }
                },
                trailingIcon = if (query.isNotBlank()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "清空搜索")
                        }
                    }
                } else {
                    null
                },
            )
        },
        expanded = false,
        onExpandedChange = {},
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth(),
        windowInsets = TopAppBarDefaults.windowInsets,
    ) {}
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
                            if (option.value.isNotBlank() && option.value != option.label) {
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

@Composable
private fun ChatSearchResults(
    query: String,
    searching: Boolean,
    results: List<ChatSearchResultDto>,
    actionsEnabled: Boolean,
    onBranch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        searching -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        results.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "没有找到相关聊天记录",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> LazyColumn(
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "找到 ${results.size} 条结果",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(
                items = results,
                key = { index, item ->
                    "${item.turnId}-${item.timestamp}-${item.message.hashCode()}-$index"
                },
            ) { _, result ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                result.speaker.ifBlank { "人物" },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (result.archived) {
                                Text(
                                    "较早记录",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HighlightedSearchText(result.message, query)
                        if (result.turnId.isNotBlank()) {
                            TextButton(
                                onClick = { onBranch(result.turnId) },
                                enabled = actionsEnabled,
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.CallSplit,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                )
                                Text("从此处分支", modifier = Modifier.padding(start = 5.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HighlightedSearchText(text: String, query: String) {
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    val annotated = remember(text, query, highlightColor) {
        buildAnnotatedString {
            var cursor = 0
            Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
                append(text.substring(cursor, match.range.first))
                pushStyle(SpanStyle(background = highlightColor, fontWeight = FontWeight.SemiBold))
                append(match.value)
                pop()
                cursor = match.range.last + 1
            }
            append(text.substring(cursor))
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Transcript(
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

@Composable
private fun PendingUserMessageBubble(
    pending: PendingUserMessage,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onReconcile: () -> Unit,
    onRecover: () -> Unit,
    requiresRecovery: Boolean,
) {
    if (pending.messageKind == "plot") {
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
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 4.dp,
            ),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(
                    "你",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                    fontWeight = FontWeight.SemiBold,
                )
                ParentheticalMessageText(
                    text = pending.message,
                    modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    baseColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                when (pending.status) {
                    PendingUserMessageStatus.Sending -> Row(
                        modifier = Modifier.padding(top = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            pending.statusText.ifBlank { "正在发送" },
                            modifier = Modifier.padding(start = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                        )
                    }

                    PendingUserMessageStatus.Failed -> {
                        Text(
                            pending.statusText.ifBlank { "发送失败" },
                            modifier = Modifier.padding(top = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (requiresRecovery) {
                            TextButton(
                                onClick = onRecover,
                                modifier = Modifier.align(Alignment.End),
                            ) { Text("恢复会话") }
                        } else {
                            Row(
                                modifier = Modifier.align(Alignment.End),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = onEdit) { Text("编辑") }
                                if (pending.retryable) {
                                    TextButton(onClick = onRetry) { Text("重试") }
                                }
                            }
                        }
                    }

                    PendingUserMessageStatus.OutcomeUnknown -> {
                        Text(
                            pending.statusText.ifBlank { "连接中断，正在核对结果" },
                            modifier = Modifier.padding(top = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(
                            onClick = if (requiresRecovery) onRecover else onReconcile,
                            modifier = Modifier.align(Alignment.End),
                        ) { Text(if (requiresRecovery) "恢复会话" else "核对结果") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingNarrationMessageCard(
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
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(
                    "旁白",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                ParentheticalMessageText(
                    text = pending.message,
                    modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    baseColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                when (pending.status) {
                    PendingUserMessageStatus.Sending -> Row(
                        modifier = Modifier.padding(top = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(
                            pending.statusText.ifBlank { "正在发送旁白" },
                            modifier = Modifier.padding(start = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.78f),
                        )
                    }

                    PendingUserMessageStatus.Failed -> {
                        Text(
                            pending.statusText.ifBlank { "旁白发送失败" },
                            modifier = Modifier.padding(top = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (requiresRecovery) {
                            TextButton(
                                onClick = onRecover,
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("恢复会话")
                            }
                        } else {
                            Row(modifier = Modifier.align(Alignment.End)) {
                                TextButton(onClick = onEdit) { Text("编辑") }
                                if (pending.retryable) TextButton(onClick = onRetry) { Text("重试") }
                            }
                        }
                    }

                    PendingUserMessageStatus.OutcomeUnknown -> {
                        Text(
                            pending.statusText.ifBlank { "连接中断，正在核对结果" },
                            modifier = Modifier.padding(top = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(
                            onClick = if (requiresRecovery) onRecover else onReconcile,
                            modifier = Modifier.align(Alignment.End),
                        ) { Text(if (requiresRecovery) "恢复会话" else "核对结果") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingDirectorInstructionCard(
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
            shape = RoundedCornerShape(0.dp),
        ) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                androidx.compose.material3.HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "导演指令",
                        modifier = Modifier.padding(start = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                ParentheticalMessageText(
                    text = pending.message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    baseColor = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                when (pending.status) {
                    PendingUserMessageStatus.Sending -> Row(
                        modifier = Modifier.padding(top = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            pending.statusText.ifBlank { "正在安排下一拍" },
                            modifier = Modifier.padding(start = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    PendingUserMessageStatus.Failed -> {
                        Text(
                            pending.statusText.ifBlank { "指令未生效" },
                            modifier = Modifier.padding(top = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (requiresRecovery) {
                            TextButton(
                                onClick = onRecover,
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("恢复会话")
                            }
                        } else {
                            Row(modifier = Modifier.align(Alignment.End)) {
                                TextButton(onClick = onEdit) { Text("编辑") }
                                if (pending.retryable) TextButton(onClick = onRetry) { Text("重试") }
                            }
                        }
                    }

                    PendingUserMessageStatus.OutcomeUnknown -> {
                        Text(
                            pending.statusText.ifBlank { "连接中断，正在核对结果" },
                            modifier = Modifier.padding(top = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(
                            onClick = if (requiresRecovery) onRecover else onReconcile,
                            modifier = Modifier.align(Alignment.End),
                        ) { Text(if (requiresRecovery) "恢复会话" else "核对结果") }
                    }
                }
            }
        }
    }
}

private suspend fun LazyListState.scrollToBottom(itemIndex: Int, animated: Boolean) {
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

@Composable
private fun ModelReasoningBlock(text: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(streaming) }
    LaunchedEffect(streaming) {
        expanded = streaming
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !streaming) { expanded = !expanded },
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (streaming) "模型推理 · 生成中" else "模型推理",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!streaming && !expanded) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "展开模型推理",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            if (streaming || expanded) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.86f),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TranscriptBubble(
    item: TranscriptItemDto,
    avatarBytes: Map<String, ByteArray>,
    displayPreferences: ChatDisplayPreferences,
    actionsEnabled: Boolean,
    includeInnerThoughts: Boolean,
    streaming: Boolean = false,
    canRegenerate: Boolean = false,
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
    val messageStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = MaterialTheme.typography.bodyMedium.fontSize * displayPreferences.fontSizeScale,
    )

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
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = verticalPadding)) {
                        Text(
                            text = "旁白${
                                item.speaker.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
                            }",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                        ParentheticalMessageText(
                            text = item.message,
                            modifier = Modifier.padding(top = 3.dp),
                            style = messageStyle.copy(fontStyle = FontStyle.Italic),
                            baseColor = MaterialTheme.colorScheme.onTertiaryContainer,
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
                    shape = RoundedCornerShape(0.dp),
                ) {
                    Column(
                        Modifier.padding(horizontal = 14.dp, vertical = verticalPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        androidx.compose.material3.HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "导演${
                                    item.speaker.takeIf(String::isNotBlank)?.let { " · $it" }
                                        .orEmpty()
                                }",
                                modifier = Modifier.padding(start = 5.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        ParentheticalMessageText(
                            text = item.message,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 3.dp),
                            style = messageStyle,
                            baseColor = MaterialTheme.colorScheme.onSurface,
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
            ChatPersonaAvatar(
                bytes = avatarBytes[item.speaker],
                name = item.speaker,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column {
            if (!isUser) {
                Text(
                    text = item.speaker.ifBlank { "人物" },
                    modifier = Modifier.padding(bottom = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 18.dp,
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = verticalPadding)) {
                        if (isUser) Text(
                            text = "你 · ${item.speaker}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                            fontWeight = FontWeight.SemiBold,
                        )
                        ParentheticalMessageText(
                            text = item.message,
                            modifier = Modifier.padding(top = 3.dp),
                            style = messageStyle,
                            baseColor = if (isUser) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (includeInnerThoughts && item.innerThought.isNotBlank() && !isUser) {
                            androidx.compose.material3.HorizontalDivider(
                                modifier = Modifier.padding(top = 6.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Text(
                                text = "内心独白：${item.innerThought}",
                                modifier = Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontStyle = FontStyle.Italic,
                                ),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (streaming) {
                            Text(
                                "正在生成",
                                modifier = Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
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

@Composable
private fun MessageContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actionsEnabled: Boolean,
    canRegenerate: Boolean,
    canBranch: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.inverseSurface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MessageContextAction(
                label = "复制",
                enabled = true,
                onClick = {
                    onDismiss()
                    onCopy()
                },
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (canRegenerate) {
                MessageContextAction(
                    label = "重新生成",
                    enabled = actionsEnabled,
                    onClick = {
                        onDismiss()
                        onRegenerate()
                    },
                ) {
                    Icon(
                        Icons.Default.Replay,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (canBranch) {
                MessageContextAction(
                    label = "从此处分支",
                    enabled = actionsEnabled,
                    onClick = {
                        onDismiss()
                        onBranch()
                    },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.CallSplit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageContextAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.inverseOnSurface
    Column(
        modifier = Modifier
            .widthIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides contentColor,
        ) {
            icon()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = if (enabled) 1f else 0.38f),
            maxLines = 1,
        )
    }
}

private val parentheticalPattern = Regex("[（(][^（）()\\n]*[）)]")

@Composable
private fun ParentheticalMessageText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
    baseColor: androidx.compose.ui.graphics.Color,
    textAlign: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Start,
) {
    val asideColor = MaterialTheme.colorScheme.secondary
    val annotated = remember(text, asideColor) {
        buildAnnotatedString {
            var cursor = 0
            parentheticalPattern.findAll(text).forEach { match ->
                append(text.substring(cursor, match.range.first))
                pushStyle(SpanStyle(color = asideColor, fontStyle = FontStyle.Italic))
                append(match.value)
                pop()
                cursor = match.range.last + 1
            }
            append(text.substring(cursor))
        }
    }
    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = baseColor,
        textAlign = textAlign
    )
}

@Composable
private fun ChatComposer(
    state: ChatUiState,
    avatarBytes: Map<String, ByteArray>,
    onDraftChange: (String) -> Unit,
    onMessageKindChange: (String) -> Unit,
    onInvokePluginAction: (ChatPluginAction) -> Unit,
    onOpenDirector: () -> Unit,
    onSend: () -> Unit,
    onToggleContinuousObserve: () -> Unit,
    onToggleGenerationEnhancer: (ChatGenerationEnhancer) -> Unit,
    onRecover: () -> Unit,
    onReconcile: () -> Unit,
    onRetry: () -> Unit,
    onDiscardRetry: () -> Unit,
) {
    var mentionsOpen by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var messageKindMenuOpen by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var pluginsOpen by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var toolsOpen by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var draftValue by rememberSaveable(state.sessionId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.draft))
    }
    val participants = remember(state.session) {
        val session = state.session ?: return@remember emptyList()
        val present = session.sceneProgress["present_participants"]
            ?.let { value ->
                runCatching {
                    value.jsonArray.mapNotNull { item ->
                        item.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                    }
                }.getOrNull()
            }
            .orEmpty()
        (present.ifEmpty { session.participants })
            .filter { it.isNotBlank() && it != session.controlledCharacter }
            .distinct()
    }
    val inputEnabled = !state.sending && !state.recovering &&
            !state.sendOutcomeUnknown && state.failedOperationId.isBlank()
    val pluginActionBusy = state.toolBusy.startsWith("plugin:")
    val draftInputEnabled = inputEnabled && state.toolBusy != "suggest" && !pluginActionBusy
    val mentionColor = MaterialTheme.colorScheme.primary
    val mentionTransformation = remember(participants, mentionColor) {
        MentionVisualTransformation(participants, mentionColor)
    }
    LaunchedEffect(state.draft) {
        if (state.draft != draftValue.text) {
            val previousSelection = draftValue.selection
            val lastIndex = state.draft.length
            draftValue = TextFieldValue(
                text = state.draft,
                selection = TextRange(
                    previousSelection.start.coerceIn(0, lastIndex),
                    previousSelection.end.coerceIn(0, lastIndex),
                ),
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(
                horizontal = 12.dp,
                vertical = if (state.chatDisplay.compactMode) 6.dp else 10.dp,
            ),
    ) {
        if (participants.isNotEmpty() && mentionsOpen) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                items(participants, key = { it }) { participant ->
                    Column(
                        modifier = Modifier
                            .width(60.dp)
                            .clickable(enabled = inputEnabled) {
                                val next = draftValue.insertMention(participant)
                                draftValue = next
                                onDraftChange(next.text)
                                mentionsOpen = false
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ChatPersonaAvatar(
                            bytes = avatarBytes[participant],
                            name = participant,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            text = participant,
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        LazyRow(
            modifier = Modifier.padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (participants.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = { mentionsOpen = !mentionsOpen },
                        enabled = inputEnabled,
                        modifier = Modifier.size(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("@") }
                }
            }
            item {
                Box {
                    OutlinedButton(
                        onClick = { pluginsOpen = true },
                        enabled = inputEnabled && (
                                state.pluginActions.isNotEmpty() ||
                                        state.generationEnhancers.isNotEmpty()
                                ),
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (pluginActionBusy) "插件运行中…" else "插件")
                    }
                    DropdownMenu(
                        expanded = pluginsOpen,
                        onDismissRequest = { pluginsOpen = false },
                    ) {
                        state.generationEnhancers.forEach { enhancer ->
                            val active = enhancer.isActive(state.session)
                            val busyKey = "enhancer:${enhancer.stateKey}"
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (state.toolBusy == busyKey) {
                                            "${enhancer.title} · 保存中…"
                                        } else {
                                            enhancer.title
                                        },
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (active) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (active) "已开启" else "已关闭",
                                    )
                                },
                                enabled = state.canUseTools,
                                onClick = {
                                    pluginsOpen = false
                                    onToggleGenerationEnhancer(enhancer)
                                },
                            )
                        }
                        state.pluginActions.forEach { action ->
                            val busyKey = "plugin:${action.pluginId}:${action.actionId}"
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (state.toolBusy == busyKey) "${action.title} · 运行中…" else action.title,
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                                },
                                enabled = state.canUseTools,
                                onClick = {
                                    pluginsOpen = false
                                    onInvokePluginAction(action)
                                },
                            )
                        }
                    }
                }
            }
            item {
                Box {
                    OutlinedButton(
                        onClick = { messageKindMenuOpen = true },
                        enabled = inputEnabled,
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(
                            messageKindOptions.firstOrNull { it.value == state.messageKind }
                                ?.label ?: "对话",
                        )
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "切换输入模式",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = messageKindMenuOpen,
                        onDismissRequest = { messageKindMenuOpen = false },
                    ) {
                        messageKindOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    onMessageKindChange(option.value)
                                    messageKindMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            item {
                Box {
                    OutlinedButton(
                        onClick = { toolsOpen = true },
                        // 连续旁观期间也要能打开工具菜单（暂停入口在里面）
                        enabled = inputEnabled || state.continuousObserveEnabled,
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text("工具")
                    }
                    DropdownMenu(
                        expanded = toolsOpen,
                        onDismissRequest = { toolsOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("剧情导演") },
                            leadingIcon = { Icon(Icons.Outlined.Movie, contentDescription = null) },
                            enabled = state.canUseTools,
                            onClick = {
                                toolsOpen = false
                                onOpenDirector()
                            },
                        )
                        if (state.session?.mode == "observe") {
                            DropdownMenuItem(
                                text = { Text(if (state.continuousObserveEnabled) "暂停旁观" else "开启旁观") },
                                leadingIcon = {
                                    Icon(
                                        if (state.continuousObserveEnabled) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                    )
                                },
                                enabled = state.canToggleContinuousObserve,
                                onClick = {
                                    toolsOpen = false
                                    onToggleContinuousObserve()
                                },
                            )
                        }
                    }
                }
            }
        }

        if (state.messageKind == "plot") {
            Text(
                "导演指令会引导下一拍，不会写成你的角色台词。",
                modifier = Modifier.padding(bottom = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draftValue,
                onValueChange = { next ->
                    val resolved = normalizeMentionDeletion(
                        previous = draftValue,
                        next = next,
                        participants = participants,
                    )
                    draftValue = resolved
                    onDraftChange(resolved.text)
                },
                modifier = Modifier
                    .weight(1f)
                    // PC 端回车直接发送；Shift/修饰键+回车保留默认行为（如换行）
                    .onPreviewKeyEvent { keyEvent ->
                        if (
                            keyEvent.type == KeyEventType.KeyDown &&
                            keyEvent.key == Key.Enter &&
                            !keyEvent.isShiftPressed &&
                            !keyEvent.isCtrlPressed &&
                            !keyEvent.isAltPressed &&
                            !keyEvent.isMetaPressed &&
                            // 输入法组合候选词时回车用于确认候选，不发送
                            draftValue.composition == null
                        ) {
                            if (state.canSend) onSend()
                            true
                        } else {
                            false
                        }
                    },
                enabled = draftInputEnabled,
                visualTransformation = mentionTransformation,
                placeholder = {
                    Text(
                        when (state.messageKind) {
                            "narration" -> "描述一个场景变化…"
                            "plot" -> "交代希望下一拍怎样发展…"
                            else -> "写下你想说的话…"
                        },
                    )
                },
                minLines = 1,
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (state.canSend) onSend() }),
            )
            Spacer(Modifier.size(2.dp))
            if (state.continuousObserveEnabled) {
                // 连续旁观中：发送区替换为常驻“暂停旁观”，保证随时可以停下
                Button(
                    onClick = onToggleContinuousObserve,
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("暂停")
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = state.canSend,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (state.canSend) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        },
                    )
                }
            }
        }

        if (state.pendingUserMessage == null && state.failedOperationId.isNotBlank() && !state.sending) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    when {
                        state.sendOutcomeUnknown ->
                            "连接中断，先核对本地结果；重试会沿用本次发送标识，不会重复生成。"

                        state.session?.status != "ready" ->
                            "回复在本机仍处于待处理状态，可重试同一次生成或恢复会话。"

                        else ->
                            "本次生成失败。重试会沿用原发送标识，也可以保留输入后修改。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (state.sendOutcomeUnknown || state.session?.status != "ready") {
                        TextButton(
                            onClick = if (state.session?.status == "ready") onReconcile else onRecover,
                            enabled = !state.recovering,
                        ) {
                            Text(if (state.session?.status == "ready") "核对状态" else "恢复会话")
                        }
                    } else {
                        TextButton(onClick = onDiscardRetry, enabled = !state.recovering) {
                            Text("编辑消息")
                        }
                    }
                    Button(onClick = onRetry, enabled = !state.recovering) {
                        Icon(
                            Icons.Default.Replay,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Text("重试", modifier = Modifier.padding(start = 5.dp))
                    }
                }
            }
        } else if (state.pendingUserMessage == null && state.sendOutcomeUnknown) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.session?.status == "ready") {
                        "上一次发送结果尚未核对，确认前不会重复发送。"
                    } else {
                        "上一次发送仍处于待处理状态，可以放弃这轮并恢复会话。"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(
                    onClick = if (state.session?.status == "ready") onReconcile else onRecover,
                    enabled = !state.sending && !state.recovering,
                ) {
                    if (state.recovering) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (state.session?.status == "ready") "核对结果" else "恢复会话")
                    }
                }
            }
        } else if (state.session?.status != "ready") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "上一次生成没有正常结束，恢复后可以继续发送。",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(
                    onClick = onRecover,
                    enabled = !state.sending && !state.recovering,
                ) {
                    if (state.recovering) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("恢复会话")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatPersonaAvatar(
    bytes: ByteArray?,
    name: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(androidx.compose.foundation.shape.CircleShape),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        val bitmap = bytes?.let { decodeImageBitmap(it) }
        if (bitmap == null) {
            Box(contentAlignment = Alignment.Center) {
                val initial = name.trim().take(1)
                if (initial.isBlank()) {
                    Icon(Icons.Outlined.Person, contentDescription = "人物头像")
                } else {
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        } else {
            androidx.compose.foundation.Image(
                bitmap = checkNotNull(bitmap),
                contentDescription = "人物头像",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(androidx.compose.foundation.shape.CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
    }
}

private class MentionVisualTransformation(
    private val participants: List<String>,
    private val mentionColor: androidx.compose.ui.graphics.Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val annotated = buildAnnotatedString {
            append(text)
            text.text.mentionRanges(participants).forEach { range ->
                addStyle(
                    SpanStyle(color = mentionColor, fontWeight = FontWeight.SemiBold),
                    range.first,
                    range.last + 1,
                )
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}

internal fun TextFieldValue.insertMention(participant: String): TextFieldValue {
    val name = participant.trim()
    if (name.isBlank() || text.mentionRanges(listOf(name)).isNotEmpty()) return this

    val start = selection.min
    val end = selection.max
    val before = text.substring(0, start)
    val after = text.substring(end)
    val prefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
    val suffix = if (after.isEmpty() || !after.first().isWhitespace()) " " else ""
    val inserted = "$prefix@$name$suffix"
    val nextText = before + inserted + after
    val cursor = before.length + inserted.length
    return TextFieldValue(nextText, TextRange(cursor))
}

internal fun normalizeMentionDeletion(
    previous: TextFieldValue,
    next: TextFieldValue,
    participants: List<String>,
): TextFieldValue {
    if (next.text.length >= previous.text.length) return next

    val change = textDeletionRange(previous.text, next.text) ?: return next
    val affected = previous.text.mentionRanges(participants).filter { mention ->
        (mention.first < change.end && mention.last + 1 > change.start) ||
                (change.start == mention.last + 1 &&
                        change.end == mention.last + 2 &&
                        previous.text.getOrNull(mention.last + 1)?.isWhitespace() == true)
    }
    if (affected.isEmpty()) return next

    val start = minOf(change.start, affected.minOf { it.first })
    val end = maxOf(
        change.end,
        affected.maxOf { mention ->
            val afterMention = mention.last + 1
            if (previous.text.getOrNull(afterMention)?.isWhitespace() == true) {
                afterMention + 1
            } else {
                afterMention
            }
        },
    )
    return TextFieldValue(
        text = previous.text.removeRange(start, end),
        selection = TextRange(start),
    )
}

internal fun String.mentionRanges(participants: List<String>): List<IntRange> = participants
    .asSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()
    .flatMap { name ->
        Regex("(?<!\\S)@${Regex.escape(name)}(?=\\s|$)")
            .findAll(this)
            .map { it.range }
    }
    .distinct()
    .sortedBy { it.first }
    .toList()

private fun textDeletionRange(previous: String, next: String): TextRange? {
    var start = 0
    val sharedLength = minOf(previous.length, next.length)
    while (start < sharedLength && previous[start] == next[start]) start += 1

    var suffix = 0
    while (
        suffix < previous.length - start &&
        suffix < next.length - start &&
        previous[previous.length - suffix - 1] == next[next.length - suffix - 1]
    ) {
        suffix += 1
    }
    val end = previous.length - suffix
    return if (start < end) TextRange(start, end) else null
}

private fun String.chineseMode(): String = when (this) {
    "act" -> "扮演人物"
    "insert" -> "自设入场"
    else -> "旁观群聊"
}

/**
 * 稳定的消息 key：不依赖列表下标，向上加载历史前插时不会导致 key 漂移。
 * （同一 turn 内同 speaker 的重复文本也几乎不可能冲突。）
 */
private fun TranscriptItemDto.transcriptKey(): String =
    "$turnId|$speaker|$timestamp|${message.hashCode()}"

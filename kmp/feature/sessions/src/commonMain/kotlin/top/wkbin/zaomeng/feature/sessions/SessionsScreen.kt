package top.wkbin.zaomeng.feature.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.Checkbox
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.EmptyStateSizeType
import io.github.suqi8.coui.kmp.basic.FloatingActionButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.RadioButton
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.TagView
import io.github.suqi8.coui.kmp.basic.TagViewDefaults
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TopAppBarSearchBar
import io.github.suqi8.coui.kmp.basic.rememberTopAppBarSearchState
import io.github.suqi8.coui.kmp.basic.topAppBarSearchResults
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.icon.extended.Search
import io.github.suqi8.coui.kmp.icon.extended.SelectAll
import io.github.suqi8.coui.kmp.icon.extended.Tasks
import io.github.suqi8.coui.kmp.overlay.OverlayListPopup
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.data.api.CardGreetingsDto
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.expandSillyTavernNames
import top.wkbin.zaomeng.platform.PlatformBackHandler
import top.wkbin.zaomeng.platform.rememberDocumentPicker
import top.wkbin.zaomeng.platform.rememberFileExporter
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiPageScaffold
import top.wkbin.zaomeng.ui.coui.CouiTextField
import top.wkbin.zaomeng.ui.coui.couiPageScroll
import top.wkbin.zaomeng.ui.coui.isWideWindow
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap
import top.wkbin.zaomeng.ui.roleplay.RoleplayMessageContent

private data class DialogueModeOption(
    val value: String,
    val label: String,
    val description: String,
)

private val dialogueModeOptions = listOf(
    DialogueModeOption("observe", "旁观", "让人物自己推进故事"),
    DialogueModeOption("act", "扮演", "代入已有故事人物"),
    DialogueModeOption("insert", "入场", "以自设身份进入场景"),
)

@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    runId: String? = null,
    showBackButton: Boolean = true,
    onBack: () -> Unit,
    onOpenChat: (runId: String, sessionId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chatPicker = rememberDocumentPicker { filename, bytes ->
        runId?.let { viewModel.importSillyTavernChat(it, filename, bytes) }
    }
    val chatSaver = rememberFileExporter(onSave = viewModel::saveChatExport, onCancelled = viewModel::cancelChatExport)
    LaunchedEffect(state.chatExportPending) {
        val exported = state.exportedChat ?: return@LaunchedEffect
        if (state.chatExportPending) {
            viewModel.consumeChatExportDestination()
            chatSaver(exported.filename, exported.mimeType)
        }
    }
    val lazySessions = viewModel.sessions.collectAsLazyPagingItems()
    var pendingDeletion by remember { mutableStateOf<DialogueSessionDto?>(null) }
    var pendingRename by remember { mutableStateOf<DialogueSessionDto?>(null) }
    var renameTitle by remember { mutableStateOf("") }
    var pendingBatchDeletion by remember { mutableStateOf(false) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val searchState = rememberTopAppBarSearchState(searchExpanded)
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissSearch = {
        searchExpanded = false
        viewModel.updateSearchQuery("")
        keyboardController?.hide()
        Unit
    }
    val visibleSessions = lazySessions.itemSnapshotList.items
    val emptySessions = lazySessions.itemCount == 0 &&
        lazySessions.loadState.refresh is LoadState.NotLoading &&
        lazySessions.loadState.append.endOfPaginationReached
    val visibleSessionKeys = visibleSessions.mapTo(mutableSetOf(), DialogueSessionDto::key)
    val allVisibleSelected = visibleSessionKeys.isNotEmpty() &&
        visibleSessionKeys.all(state.selectedSessionKeys::contains)

    PlatformBackHandler(enabled = state.selectionMode || searchExpanded) {
        if (state.selectionMode) {
            viewModel.exitSelectionMode()
        } else {
            dismissSearch()
        }
    }

    LaunchedEffect(runId) {
        viewModel.load(runId)
    }
    LifecycleResumeEffect(runId) {
        viewModel.onScreenResumed()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.createdSession?.sessionId) {
        state.createdSession?.let { session ->
            viewModel.consumeCreatedSession()
            onOpenChat(session.runId, session.sessionId)
        }
    }
    LaunchedEffect(state.selectedSessionKeys.size) {
        if (state.selectedSessionKeys.isEmpty()) pendingBatchDeletion = false
    }
    LaunchedEffect(lazySessions.itemCount, state.deletingSessionKeys) {
        pendingDeletion?.let { pending ->
            if (visibleSessions.none { it.key == pending.key }) pendingDeletion = null
        }
    }
    LaunchedEffect(lazySessions.itemCount) {
        viewModel.ensureAvatars(visibleSessions)
    }

    var showSessionActions by remember { mutableStateOf(false) }
    val canImportChat = !runId.isNullOrBlank() && !state.loading &&
        !state.transferringChat && state.exportedChat == null

    CouiPageScaffold(
        title = when {
            state.selectionMode -> "已选择 ${state.selectedSessionKeys.size} 项"
            runId.isNullOrBlank() -> "对话"
            else -> "本书对话"
        },
        subtitle = if (!state.selectionMode && !runId.isNullOrBlank()) {
            state.runs.firstOrNull { it.runId == runId }?.title.orEmpty()
        } else {
            ""
        },
        onBack = if (showBackButton || state.selectionMode) {
            {
                if (!state.deletingSelection) {
                    if (state.selectionMode) {
                        viewModel.exitSelectionMode()
                    } else {
                        onBack()
                    }
                }
            }
        } else {
            null
        },
        wide = isWideWindow(),
        searchState = searchState,
        topBarBottomContent = {
            if (!state.selectionMode) {
                TopAppBarSearchBar(
                    query = state.searchQuery,
                    onQueryChange = viewModel::updateSearchQuery,
                    expanded = searchExpanded,
                    onExpandedChange = { expanded ->
                        if (expanded) searchExpanded = true else dismissSearch()
                    },
                    cancelText = "取消",
                    label = "搜索会话",
                )
            }
        },
        actions = {
            if (state.selectionMode) {
                IconButton(
                    onClick = { viewModel.toggleAllVisibleSessions(visibleSessionKeys) },
                    enabled = visibleSessionKeys.isNotEmpty() && !state.deletingSelection,
                ) {
                    Icon(
                        COUIIcons.SelectAll,
                        contentDescription = if (allVisibleSelected) "取消全选当前结果" else "全选当前结果",
                    )
                }
                IconButton(
                    onClick = { pendingBatchDeletion = true },
                    enabled = state.selectedSessionKeys.isNotEmpty() && !state.deletingSelection,
                ) {
                    if (state.deletingSelection) {
                        CircularProgressIndicator(strokeWidth = 2.dp, size = 22.dp)
                    } else {
                        Icon(
                            COUIIcons.Delete,
                            contentDescription = "删除选中会话",
                            tint = COUITheme.colorScheme.error,
                        )
                    }
                }
            } else {
                Box {
                    IconButton(onClick = { showSessionActions = true }) {
                        Icon(COUIIcons.More, contentDescription = "更多会话操作")
                    }
                    OverlayListPopup(
                        show = showSessionActions,
                        onDismissRequest = { showSessionActions = false },
                    ) {
                        ListPopupColumn {
                            val actions = buildList {
                                if (!runId.isNullOrBlank()) {
                                    add(
                                        DropdownItem(
                                            text = "导入聊天",
                                            enabled = canImportChat,
                                            onClick = {
                                                showSessionActions = false
                                                chatPicker()
                                            },
                                        ),
                                    )
                                }
                                add(
                                    DropdownItem(
                                        text = "多选管理会话",
                                        enabled = lazySessions.itemCount > 0 && state.deletingSessionKeys.isEmpty(),
                                        onClick = {
                                            showSessionActions = false
                                            viewModel.enterSelectionMode()
                                        },
                                        icon = { modifier ->
                                            Icon(COUIIcons.Tasks, contentDescription = null, modifier = modifier)
                                        },
                                    ),
                                )
                                add(
                                    DropdownItem(
                                        text = if (state.refreshing) "正在刷新…" else "刷新会话",
                                        enabled = !state.loading && !state.refreshing && !state.creating &&
                                            state.deletingSessionKeys.isEmpty(),
                                        onClick = {
                                            showSessionActions = false
                                            viewModel.refresh()
                                        },
                                        icon = { modifier ->
                                            Icon(COUIIcons.Refresh, contentDescription = null, modifier = modifier)
                                        },
                                    ),
                                )
                            }
                            actions.forEachIndexed { index, item ->
                                DropdownImpl(
                                    item = item,
                                    optionSize = actions.size,
                                    isSelected = false,
                                    index = index,
                                    onSelectedIndexChange = { item.onClick?.invoke() },
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::openCreateDialog,
                visible = !state.selectionMode && (!emptySessions || state.searchQuery.isNotBlank()),
            ) {
                Icon(COUIIcons.Add, contentDescription = "新建对话")
            }
        },
    ) { scope ->
        when {
            state.loading -> LoadingSessions(
                modifier = Modifier.padding(scope.contentPadding),
                error = state.error,
                onDismissError = viewModel::clearError,
            )

            else -> SessionsContent(
                state = state,
                lazySessions = lazySessions,
                visibleSessions = visibleSessions,
                onOpenChat = onOpenChat,
                onCreateSession = viewModel::openCreateDialog,
                onDelete = { pendingDeletion = it },
                onExport = viewModel::exportSillyTavernChat,
                onRename = {
                    pendingRename = it
                    renameTitle = it.title
                },
                onToggleSelection = viewModel::toggleSessionSelection,
                onSearchQueryChange = viewModel::updateSearchQuery,
                onSelectSort = viewModel::selectSort,
                onDismissError = viewModel::clearError,
                onRetry = lazySessions::retry,
                contentPadding = scope.contentPadding,
                modifier = Modifier.couiPageScroll(scope).topAppBarSearchResults(searchState),
            )
        }

        if (state.createDialogVisible) {
            NewSessionDialog(
                state = state,
                onDismiss = viewModel::closeCreateDialog,
                onSelectRun = viewModel::selectRun,
                onSelectMode = viewModel::selectMode,
                onToggleParticipant = viewModel::toggleParticipant,
                onSelectControlled = viewModel::selectControlledCharacter,
                onSelfNameChange = viewModel::updateSelfName,
                onSelfIdentityChange = viewModel::updateSelfIdentity,
                onSelfStyleChange = viewModel::updateSelfStyle,
                onSelectOpeningPreset = viewModel::selectOpeningPreset,
                onSelectSceneCard = viewModel::selectSceneCard,
                onRecommendSceneCard = viewModel::recommendSceneCard,
                onSelectSelfCard = viewModel::selectSelfCard,
                loadCardGreetings = viewModel::getCardGreetings,
                onCreate = viewModel::createSession,
            )
        }

        pendingDeletion?.let { session ->
            val deleting = session.key in state.deletingSessionKeys
            CouiAlertDialog(
                onDismissRequest = { if (!deleting) pendingDeletion = null },
                title = "删除这段会话？",
                confirmText = "删除",
                onConfirm = { viewModel.deleteSession(session) },
                confirmEnabled = !deleting,
                dismissText = "取消",
                onDismiss = { if (!deleting) pendingDeletion = null },
                body = {
                    Text("聊天记录会从这台手机上永久删除，人物资料和书卷不会受影响。")
                },
            )
        }

        pendingRename?.let { session ->
            CouiAlertDialog(
                onDismissRequest = { pendingRename = null },
                title = "修改会话标题",
                confirmText = "保存",
                onConfirm = {
                    viewModel.renameSession(session, renameTitle)
                    pendingRename = null
                },
                confirmEnabled = renameTitle.isNotBlank(),
                dismissText = "取消",
                onDismiss = { pendingRename = null },
                body = {
                    CouiTextField(
                        value = renameTitle,
                        onValueChange = { renameTitle = it.take(80) },
                        modifier = Modifier.fillMaxWidth(),
                        label = "会话标题",
                        singleLine = true,
                    )
                },
            )
        }

        if (pendingBatchDeletion && state.selectedSessionKeys.isNotEmpty()) {
            val selectedCount = state.selectedSessionKeys.size
            CouiAlertDialog(
                onDismissRequest = { pendingBatchDeletion = false },
                title = "删除选中的 $selectedCount 个会话？",
                confirmText = "删除 $selectedCount 个",
                onConfirm = {
                    pendingBatchDeletion = false
                    viewModel.deleteSelectedSessions()
                },
                dismissText = "取消",
                onDismiss = { pendingBatchDeletion = false },
                body = {
                    Text("这些聊天记录会从这台手机上永久删除，人物资料和书卷不会受影响。")
                },
            )
        }
    }
}

@Composable
private fun LoadingSessions(
    modifier: Modifier = Modifier,
    error: String = "",
    onDismissError: () -> Unit = {},
) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        if (error.isNotBlank()) {
            ErrorCard(message = error, onDismiss = onDismissError)
        }
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(size = 40.dp)
                Spacer(Modifier.height(12.dp))
                Text("正在整理本机会话…", color = COUITheme.colorScheme.labelSecondary)
            }
        }
    }
}

@Composable
private fun SessionsContent(
    state: SessionsUiState,
    lazySessions: LazyPagingItems<DialogueSessionDto>,
    visibleSessions: List<DialogueSessionDto>,
    onOpenChat: (String, String) -> Unit,
    onCreateSession: () -> Unit,
    onDelete: (DialogueSessionDto) -> Unit,
    onExport: (DialogueSessionDto) -> Unit,
    onRename: (DialogueSessionDto) -> Unit,
    onToggleSelection: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectSort: (SessionsSort) -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val runsById = remember(state.runs) { state.runs.associateBy(RunManifestDto::runId) }
    val refreshState = lazySessions.loadState.refresh
    val appendState = lazySessions.loadState.append
    val initialLoading = refreshState is LoadState.Loading && lazySessions.itemCount == 0
    val isEmpty = lazySessions.itemCount == 0 &&
        refreshState is LoadState.NotLoading &&
        appendState.endOfPaginationReached
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 380.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 104.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.error.isNotBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ErrorCard(message = state.error, onDismiss = onDismissError)
            }
        }

        when {
            initialLoading -> item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(size = 40.dp)
                }
            }

            refreshState is LoadState.Error && lazySessions.itemCount == 0 -> item(
                span = { GridItemSpan(maxLineSpan) },
            ) {
                ErrorCard(
                    message = refreshState.error.message
                        ?.takeIf(String::isNotBlank)
                        ?: "会话加载失败，请稍后重试。",
                    onDismiss = onRetry,
                )
            }

            isEmpty -> if (state.searchQuery.isNotBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    NoSessionMatches(onClearSearch = { onSearchQueryChange("") })
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        colors = CardDefaults.defaultColors(color = COUITheme.colorScheme.cardBackground),
                    ) {
                        EmptyState(
                            title = "还没有聊天记录",
                            subtitle = if (state.scopedRunId != null) {
                                "选择参与人物，开启这本书的第一段对话。"
                            } else {
                                "选择一本书卷和参与人物，开始对话。"
                            },
                            actionText = "新建对话",
                            onActionClick = onCreateSession,
                            sizeType = EmptyStateSizeType.Small,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                }
            }

            else -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SessionListControls(
                        sort = state.sort,
                        visibleCount = visibleSessions.size,
                        totalCount = state.totalSessions ?: visibleSessions.size,
                        enabled = !state.deletingSelection,
                        onSelectSort = onSelectSort,
                    )
                }
                items(count = lazySessions.itemCount, key = lazySessions.itemKey(DialogueSessionDto::key)) { index ->
                    val session = lazySessions[index]
                    if (session != null) {
                        SessionCard(
                            session = session,
                            bookTitle = runsById[session.runId]?.title?.takeIf(String::isNotBlank)
                                ?: session.novelId,
                            avatarBytes = state.avatarBytes,
                            deleting = session.key in state.deletingSessionKeys,
                            selectionMode = state.selectionMode,
                            selected = session.key in state.selectedSessionKeys,
                            onOpen = { onOpenChat(session.runId, session.sessionId) },
                            onDelete = { onDelete(session) },
                            onExport = { onExport(session) },
                            exportEnabled = !state.transferringChat && state.exportedChat == null,
                            onRename = { onRename(session) },
                            onToggleSelection = { onToggleSelection(session.key) },
                        )
                    }
                }
                when {
                    appendState is LoadState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp, size = 22.dp)
                        }
                    }

                    appendState is LoadState.Error -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = appendState.error.message
                                    ?.takeIf(String::isNotBlank)
                                    ?: "加载更多会话失败",
                                style = COUITheme.textStyles.bodyXS,
                                color = COUITheme.colorScheme.labelSecondary,
                            )
                            TextButton(
                                text = "重试",
                                onClick = onRetry,
                                colors = ButtonDefaults.textButtonColors(textColor = COUITheme.colorScheme.primary),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionListControls(
    sort: SessionsSort,
    visibleCount: Int,
    totalCount: Int,
    enabled: Boolean,
    onSelectSort: (SessionsSort) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (visibleCount == totalCount) "$totalCount 个会话" else "找到 $visibleCount 个",
                modifier = Modifier.weight(1f),
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.labelSecondary,
            )
            SessionsSort.values().forEach { option ->
                Chip(
                    selected = sort == option,
                    onClick = { onSelectSort(option) },
                    enabled = enabled,
                    label = option.label,
                )
            }
        }
    }
}

@Composable
private fun NoSessionMatches(onClearSearch: () -> Unit) {
    Card(
        colors = CardDefaults.defaultColors(color = COUITheme.colorScheme.cardBackground),
    ) {
        EmptyState(
            title = "没有找到相关会话",
            subtitle = "可以换个书名、人物名或消息关键词。",
            actionText = "清空搜索",
            onActionClick = onClearSearch,
            sizeType = EmptyStateSizeType.Small,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        )
    }
}

@Composable
private fun SessionCard(
    session: DialogueSessionDto,
    bookTitle: String,
    avatarBytes: Map<String, ByteArray>,
    deleting: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    exportEnabled: Boolean,
    onRename: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = if (selected) {
                COUITheme.colorScheme.secondaryContainer
            } else {
                COUITheme.colorScheme.cardBackground
            },
        ),
        onClick = if (deleting) {
            null
        } else {
            { if (selectionMode) onToggleSelection() else onOpen() }
        },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.heightIn(min = 34.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.title.ifBlank { session.participants.joinToString("、") },
                    modifier = Modifier.weight(1f),
                    style = COUITheme.textStyles.headlineXS,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selectionMode && selected) {
                    Text(
                        text = "已选择",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (!selectionMode) {
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = !deleting) {
                            if (deleting) {
                                CircularProgressIndicator(strokeWidth = 2.dp, size = 18.dp)
                            } else {
                                Icon(COUIIcons.More, contentDescription = "对话选项")
                            }
                        }
                        OverlayListPopup(
                            show = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            ListPopupColumn {
                                val actions = listOf(
                                    DropdownItem(
                                        text = "重命名",
                                        onClick = {
                                            menuOpen = false
                                            onRename()
                                        },
                                    ),
                                    DropdownItem(
                                        text = "导出聊天记录",
                                        enabled = exportEnabled,
                                        onClick = {
                                            menuOpen = false
                                            onExport()
                                        },
                                    ),
                                    DropdownItem(
                                        text = "删除对话",
                                        alert = true,
                                        onClick = {
                                            menuOpen = false
                                            onDelete()
                                        },
                                    ),
                                )
                                actions.forEachIndexed { index, item ->
                                    DropdownImpl(
                                        item = item,
                                        optionSize = actions.size,
                                        isSelected = false,
                                        index = index,
                                        onSelectedIndexChange = { item.onClick?.invoke() },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                ParticipantAvatarStack(
                    participants = session.participants,
                    runId = session.runId,
                    avatarBytes = avatarBytes,
                    modifier = Modifier.weight(1f),
                )
                val ready = session.status == "ready"
                val colors = COUITheme.colorScheme
                TagView(
                    text = if (ready) "可继续" else "待处理",
                    colors = TagViewDefaults.tagViewColors(
                        containerColor = if (ready) colors.containerBlue else colors.containerViolet,
                        contentColor = if (ready) colors.blue else colors.violet,
                    ),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = listOf(bookTitle, session.mode.chineseMode(), session.updatedAt.toLocalDateTimeDisplay())
                        .filter(String::isNotBlank).joinToString(" · "),
                    modifier = Modifier.fillMaxWidth(),
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ParticipantAvatarStack(
    participants: List<String>,
    runId: String,
    avatarBytes: Map<String, ByteArray>,
    modifier: Modifier = Modifier,
) {
    val visibleParticipants = participants.filter(String::isNotBlank).take(5)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (visibleParticipants.isEmpty()) {
            InitialAvatar(label = "？", color = COUITheme.colorScheme.containerSolid)
        } else {
            visibleParticipants.forEachIndexed { index, participant ->
                val color = when (index % 3) {
                    0 -> COUITheme.colorScheme.containerBlue
                    1 -> COUITheme.colorScheme.secondaryContainer
                    else -> COUITheme.colorScheme.containerViolet
                }
                PersonaAvatar(
                    name = participant,
                    bytes = avatarBytes["$runId|$participant"],
                    color = color,
                )
            }
            val remainingCount = participants.count(String::isNotBlank) - visibleParticipants.size
            if (remainingCount > 0) {
                InitialAvatar(label = "+$remainingCount", color = COUITheme.colorScheme.containerSolid)
            }
        }
    }
}

@Composable
private fun InitialAvatar(label: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        modifier = Modifier.size(30.dp),
        color = color,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = COUITheme.textStyles.bodyXS, maxLines = 1)
        }
    }
}

@Composable
private fun PersonaAvatar(
    name: String,
    bytes: ByteArray?,
    color: androidx.compose.ui.graphics.Color,
) {
    Surface(
        modifier = Modifier.size(30.dp),
        color = color,
        shape = CircleShape,
    ) {
        val bitmap = remember(bytes) { bytes?.let { decodeImageBitmap(it) } }
        if (bitmap == null) {
            Box(contentAlignment = Alignment.Center) {
                Text(name.trim().take(1), style = COUITheme.textStyles.bodyXS, maxLines = 1)
            }
        } else {
            androidx.compose.foundation.Image(
                bitmap = checkNotNull(bitmap),
                contentDescription = "$name 的头像",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.defaultColors(
            color = COUITheme.colorScheme.containerRed,
            contentColor = COUITheme.colorScheme.red,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = COUITheme.textStyles.bodyM,
                color = COUITheme.colorScheme.red,
            )
            TextButton(
                text = "知道了",
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(textColor = COUITheme.colorScheme.red),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun NewSessionDialog(
    state: SessionsUiState,
    onDismiss: () -> Unit,
    onSelectRun: (String) -> Unit,
    onSelectMode: (String) -> Unit,
    onToggleParticipant: (String) -> Unit,
    onSelectControlled: (String) -> Unit,
    onSelfNameChange: (String) -> Unit,
    onSelfIdentityChange: (String) -> Unit,
    onSelfStyleChange: (String) -> Unit,
    onSelectOpeningPreset: (String) -> Unit,
    onSelectSceneCard: (String) -> Unit,
    onRecommendSceneCard: () -> Unit,
    onSelectSelfCard: (String) -> Unit,
    loadCardGreetings: suspend (String) -> CardGreetingsDto,
    onCreate: (Int?) -> Unit,
) {
    val greetingCharacter = state.draft.participants.singleOrNull()?.takeIf {
        state.draft.mode == "insert" && state.draft.sceneCardId.isBlank() && state.draft.sceneProfile.isEmpty()
    }
    val greetingRun = state.draft.runId.takeIf { greetingCharacter != null && it.isNotBlank() }
    var greetings by remember(greetingRun, greetingCharacter) { mutableStateOf<CardGreetingsDto?>(null) }
    var greetingIndex by rememberSaveable(greetingRun, greetingCharacter) { mutableStateOf<Int?>(null) }
    var greetingLoadError by remember(greetingRun, greetingCharacter) { mutableStateOf(false) }
    var greetingReload by remember(greetingRun, greetingCharacter) { mutableStateOf(0) }
    LaunchedEffect(greetingRun, greetingCharacter, greetingReload) {
        val runId = greetingRun ?: return@LaunchedEffect
        greetingLoadError = false
        try {
            greetings = loadCardGreetings(runId).takeIf { it.character == greetingCharacter }
            if (greetings?.options?.any { it.index == greetingIndex } != true) greetingIndex = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            greetingIndex = null
            greetingLoadError = true
        }
    }
    CouiAlertDialog(
        onDismissRequest = { if (!state.creating) onDismiss() },
        title = "开始一段新会话",
        confirmText = if (state.creating) "创建中" else "进入场景",
        onConfirm = { onCreate(greetingIndex?.takeIf { index -> greetings?.options?.any { it.index == index } == true }) },
        confirmEnabled = state.canCreate && !state.creating,
        dismissText = "取消",
        onDismiss = { if (!state.creating) onDismiss() },
        body = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Text("选择书卷", style = COUITheme.textStyles.bodyM)
                    Spacer(Modifier.height(6.dp))
                    RunPicker(
                        runs = state.runs.filter { it.availableCharacters.isNotEmpty() },
                        selectedRunId = state.draft.runId,
                        enabled = !state.creating && state.scopedRunId == null,
                        onSelect = onSelectRun,
                    )
                }

                if (state.openingPresets.isNotEmpty()) {
                    item {
                        ReusableCardPicker(
                            label = "开场预设",
                            cards = state.openingPresets,
                            selectedCardId = state.draft.openingPresetId,
                            titleKey = "title",
                            noneLabel = "不使用预设",
                            enabled = !state.creating,
                            onSelect = onSelectOpeningPreset,
                        )
                    }
                }

                item {
                    Text("你的身份", style = COUITheme.textStyles.bodyM)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(dialogueModeOptions, key = DialogueModeOption::value) { option ->
                            Chip(
                                selected = state.draft.mode == option.value,
                                onClick = { onSelectMode(option.value) },
                                enabled = !state.creating,
                                label = option.label,
                            )
                        }
                    }
                    Text(
                        dialogueModeOptions.first { it.value == state.draft.mode }.description,
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }

                item {
                    Text("出场人物", style = COUITheme.textStyles.bodyM)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.availableCharacters.forEach { character ->
                            Row(
                                modifier = Modifier
                                    .width(190.dp)
                                    .clickable(enabled = !state.creating) { onToggleParticipant(character) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    state = ToggleableState(character in state.draft.participants),
                                    onClick = null,
                                    enabled = !state.creating,
                                )
                                Text(character, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                if (state.draft.mode == "act") {
                    item {
                        Text("你要扮演谁", style = COUITheme.textStyles.bodyM)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            state.draft.participants.forEach { character ->
                                Row(
                                    modifier = Modifier
                                        .width(190.dp)
                                        .clickable(enabled = !state.creating) { onSelectControlled(character) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = state.draft.controlledCharacter == character,
                                        onClick = null,
                                        enabled = !state.creating,
                                    )
                                    Text(character, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                if (state.draft.mode == "insert") {
                    val options = greetings?.options.orEmpty()
                    if (greetingLoadError) item {
                        TextButton(
                            text = "重试读取角色卡开场",
                            onClick = { greetingReload++ },
                            enabled = !state.creating,
                        )
                    }
                    if (options.isNotEmpty()) {
                        item {
                            Text("角色卡开场", style = COUITheme.textStyles.bodyM)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    Chip(
                                        selected = greetingIndex == null,
                                        onClick = { greetingIndex = null },
                                        enabled = !state.creating,
                                        label = "默认",
                                    )
                                }
                                items(options.filter { it.index > 0 }, key = { it.index }) { option ->
                                    Chip(
                                        selected = greetingIndex == option.index,
                                        onClick = { greetingIndex = option.index },
                                        enabled = !state.creating,
                                        label = "备用开场 ${option.index}",
                                    )
                                }
                            }
                            val text = options.firstOrNull { it.index == (greetingIndex ?: 0) }?.text
                            RoleplayMessageContent(
                                text = text?.let {
                                    expandSillyTavernNames(it, greetingCharacter.orEmpty(), state.draft.selfName.trim().ifBlank { "你" })
                                } ?: "由模型生成开场",
                                maxProseLines = 8,
                                style = COUITheme.textStyles.bodyXS,
                                baseColor = COUITheme.colorScheme.labelPrimary,
                            )
                        }
                    }
                }

                item {
                    ReusableCardPicker(
                        label = "场景卡",
                        cards = state.sceneCards,
                        selectedCardId = state.draft.sceneCardId,
                        titleKey = "title",
                        noneLabel = "不指定场景",
                        enabled = !state.creating,
                        onSelect = onSelectSceneCard,
                    )
                    Button(
                        onClick = onRecommendSceneCard,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        enabled = !state.creating && !state.recommendingScene &&
                            state.draft.participants.isNotEmpty() && state.sceneCards.isNotEmpty(),
                    ) {
                        if (state.recommendingScene) {
                            CircularProgressIndicator(strokeWidth = 2.dp, size = 18.dp)
                        }
                        Text(
                            if (state.recommendingScene) "正在推荐…" else "按人物与模式推荐场景",
                            modifier = Modifier.padding(start = if (state.recommendingScene) 8.dp else 0.dp),
                            color = COUITheme.colorScheme.labelPrimary,
                        )
                    }
                }

                if (state.draft.mode == "insert") {
                    item {
                        ReusableCardPicker(
                            label = "自设卡",
                            cards = state.selfCards,
                            selectedCardId = state.draft.selfCardId,
                            titleKey = "display_name",
                            noneLabel = "临时填写身份",
                            enabled = !state.creating,
                            onSelect = onSelectSelfCard,
                        )
                    }
                    item {
                        CouiTextField(
                            value = state.draft.selfName,
                            onValueChange = onSelfNameChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.creating,
                            label = "你的名字",
                            singleLine = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        CouiTextField(
                            value = state.draft.selfIdentity,
                            onValueChange = onSelfIdentityChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.creating,
                            label = "场景身份",
                            singleLine = false,
                            minLines = 2,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(10.dp))
                        CouiTextField(
                            value = state.draft.selfStyle,
                            onValueChange = onSelfStyleChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.creating,
                            label = "互动风格",
                            singleLine = false,
                            minLines = 2,
                            maxLines = 3,
                        )
                    }
                }

                if (state.error.isNotBlank()) {
                    item {
                        Text(
                            text = state.error,
                            color = COUITheme.colorScheme.error,
                            style = COUITheme.textStyles.bodyXS,
                        )
                    }
                }

                if (state.creating) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(strokeWidth = 2.dp, size = 18.dp)
                            Text(
                                "正在让人物进入场景，模型生成开场可能需要一会儿…",
                                modifier = Modifier.padding(start = 10.dp),
                                style = COUITheme.textStyles.bodyXS,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ReusableCardPicker(
    label: String,
    cards: List<ReusableCardDto>,
    selectedCardId: String,
    titleKey: String,
    noneLabel: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = cards.firstOrNull { it.cardId == selectedCardId }
    val selectedTitle = selected?.preview?.get(titleKey)?.jsonPrimitive?.contentOrNull
        ?: selected?.fields?.get(titleKey)?.jsonPrimitive?.contentOrNull
    Text(label, style = COUITheme.textStyles.bodyM)
    Spacer(Modifier.height(6.dp))
    Box(Modifier.fillMaxWidth()) {
        Button(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            minWidth = 0.dp,
        ) {
            Text(
                selectedTitle?.takeIf(String::isNotBlank) ?: noneLabel,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = COUITheme.colorScheme.labelPrimary,
            )
        }
        OverlayListPopup(show = expanded, onDismissRequest = { expanded = false }) {
            ListPopupColumn {
                val options = buildList {
                    add(noneLabel to "")
                    cards.forEach { card ->
                        val title = card.preview[titleKey]?.jsonPrimitive?.contentOrNull
                            ?: card.fields[titleKey]?.jsonPrimitive?.contentOrNull
                            ?: card.cardId
                        add(title to card.cardId)
                    }
                }
                options.forEachIndexed { index, option ->
                    DropdownImpl(
                        text = option.first,
                        optionSize = options.size,
                        isSelected = option.second == selectedCardId,
                        index = index,
                        onSelectedIndexChange = {
                            expanded = false
                            onSelect(option.second)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RunPicker(
    runs: List<RunManifestDto>,
    selectedRunId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = runs.firstOrNull { it.runId == selectedRunId }
    Box(Modifier.fillMaxWidth()) {
        Button(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && runs.isNotEmpty(),
            minWidth = 0.dp,
        ) {
            Text(
                text = selected?.title ?: "没有可用书卷",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = COUITheme.colorScheme.labelPrimary,
            )
        }
        OverlayListPopup(show = expanded, onDismissRequest = { expanded = false }) {
            ListPopupColumn {
                runs.forEachIndexed { index, run ->
                    DropdownImpl(
                        text = "${run.title} · ${run.availableCharacters.size} 人",
                        optionSize = runs.size,
                        isSelected = run.runId == selectedRunId,
                        index = index,
                        onSelectedIndexChange = {
                            expanded = false
                            onSelect(run.runId)
                        },
                    )
                }
            }
        }
    }
}

private fun String.chineseMode(): String = when (this) {
    "act" -> "扮演人物"
    "insert" -> "自设入场"
    else -> "旁观群聊"
}

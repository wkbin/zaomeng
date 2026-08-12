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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import top.wkbin.zaomeng.platform.PlatformBackHandler
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    runId: String? = null,
    showBackButton: Boolean = true,
    onBack: () -> Unit,
    onOpenChat: (runId: String, sessionId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lazySessions = viewModel.sessions.collectAsLazyPagingItems()
    var pendingDeletion by remember { mutableStateOf<DialogueSessionDto?>(null) }
    var pendingRename by remember { mutableStateOf<DialogueSessionDto?>(null) }
    var renameTitle by remember { mutableStateOf("") }
    var pendingBatchDeletion by remember { mutableStateOf(false) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissSearch = {
        searchExpanded = false
        viewModel.updateSearchQuery("")
        keyboardController?.hide()
        Unit
    }
    val visibleSessions = buildList {
        repeat(lazySessions.itemCount) { index ->
            lazySessions[index]?.let(::add)
        }
    }
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
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    LaunchedEffect(lazySessions.itemCount, state.deletingSessionKeys) {
        pendingDeletion?.let { pending ->
            if (visibleSessions.none { it.key == pending.key }) pendingDeletion = null
        }
    }
    LaunchedEffect(lazySessions.itemCount) {
        viewModel.ensureAvatars(visibleSessions)
    }

    Scaffold(
        topBar = {
            if (searchExpanded) {
                SessionsSearchTopBar(
                    query = state.searchQuery,
                    searchFocusRequester = searchFocusRequester,
                    onQueryChange = viewModel::updateSearchQuery,
                    onClose = dismissSearch,
                )
            } else TopAppBar(
                title = {
                    if (state.selectionMode) {
                        Text("已选择 ${state.selectedSessionKeys.size} 项")
                    } else {
                        Column {
                            Text(if (runId.isNullOrBlank()) "全部会话" else "书中会话")
                            if (!runId.isNullOrBlank()) {
                                Text(
                                    text = state.runs.firstOrNull { it.runId == runId }?.title.orEmpty(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (showBackButton || state.selectionMode) {
                        IconButton(
                            onClick = {
                                when {
                                    state.selectionMode -> viewModel.exitSelectionMode()
                                    else -> onBack()
                                }
                            },
                            enabled = !state.deletingSelection,
                        ) {
                            if (state.selectionMode) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "退出多选",
                                )
                            } else {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    }
                },
                actions = {
                    if (state.selectionMode) {
                        IconButton(
                            onClick = { viewModel.toggleAllVisibleSessions(visibleSessionKeys) },
                            enabled = visibleSessionKeys.isNotEmpty() && !state.deletingSelection,
                        ) {
                            Icon(
                                Icons.Default.SelectAll,
                                contentDescription = if (allVisibleSelected) "取消全选当前结果" else "全选当前结果",
                            )
                        }
                        IconButton(
                            onClick = { pendingBatchDeletion = true },
                            enabled = state.selectedSessionKeys.isNotEmpty() && !state.deletingSelection,
                        ) {
                            if (state.deletingSelection) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除选中会话",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    } else {
                        IconButton(
                            onClick = { searchExpanded = true },
                            enabled = lazySessions.itemCount > 0 && state.deletingSessionKeys.isEmpty(),
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "搜索会话")
                        }
                        IconButton(
                            onClick = viewModel::enterSelectionMode,
                            enabled = lazySessions.itemCount > 0 && state.deletingSessionKeys.isEmpty(),
                        ) {
                            Icon(Icons.Default.Checklist, contentDescription = "多选管理会话")
                        }
                        IconButton(
                            onClick = viewModel::refresh,
                            enabled = !state.loading && !state.refreshing && !state.creating &&
                                state.deletingSessionKeys.isEmpty(),
                        ) {
                            if (state.refreshing) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新会话")
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::openCreateDialog,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("开始新会话") },
                )
            }
        },
    ) { innerPadding ->
        when {
            state.loading -> LoadingSessions(
                modifier = Modifier.padding(innerPadding),
                error = state.error,
                onDismissError = viewModel::clearError,
            )
            else -> SessionsContent(
                state = state,
                lazySessions = lazySessions,
                visibleSessions = visibleSessions,
                onOpenChat = onOpenChat,
                onDelete = { pendingDeletion = it },
                onRename = {
                    pendingRename = it
                    renameTitle = it.title
                },
                onToggleSelection = viewModel::toggleSessionSelection,
                onSearchQueryChange = viewModel::updateSearchQuery,
                onSelectSort = viewModel::selectSort,
                onDismissError = viewModel::clearError,
                onRetry = lazySessions::retry,
                modifier = Modifier.padding(innerPadding),
            )
        }
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
            onCreate = viewModel::createSession,
        )
    }

    pendingDeletion?.let { session ->
        val deleting = session.key in state.deletingSessionKeys
        AlertDialog(
            onDismissRequest = { if (!deleting) pendingDeletion = null },
            title = { Text("删除这段会话？") },
            text = { Text("聊天记录会从这台手机上永久删除，人物资料和书卷不会受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSession(session)
                    },
                    enabled = !deleting,
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }, enabled = !deleting) { Text("取消") }
            },
        )
    }

    pendingRename?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("修改会话标题") },
            text = {
                OutlinedTextField(
                    value = renameTitle,
                    onValueChange = { renameTitle = it.take(80) },
                    label = { Text("会话标题") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameSession(session, renameTitle)
                        pendingRename = null
                    },
                    enabled = renameTitle.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text("取消") }
            },
        )
    }

    if (pendingBatchDeletion && state.selectedSessionKeys.isNotEmpty()) {
        val selectedCount = state.selectedSessionKeys.size
        AlertDialog(
            onDismissRequest = { pendingBatchDeletion = false },
            title = { Text("删除选中的 $selectedCount 个会话？") },
            text = { Text("这些聊天记录会从这台手机上永久删除，人物资料和书卷不会受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingBatchDeletion = false
                        viewModel.deleteSelectedSessions()
                    },
                ) {
                    Text("删除 $selectedCount 个", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatchDeletion = false }) { Text("取消") }
            },
        )
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
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在整理本机会话…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsSearchTopBar(
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
                placeholder = { Text("搜索会话") },
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
private fun SessionsContent(
    state: SessionsUiState,
    lazySessions: LazyPagingItems<DialogueSessionDto>,
    visibleSessions: List<DialogueSessionDto>,
    onOpenChat: (String, String) -> Unit,
    onDelete: (DialogueSessionDto) -> Unit,
    onRename: (DialogueSessionDto) -> Unit,
    onToggleSelection: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectSort: (SessionsSort) -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val runsById = remember(state.runs) { state.runs.associateBy(RunManifestDto::runId) }
    val sessionGroups = remember(visibleSessions, runsById) {
        visibleSessions.groupBy { session ->
            runsById[session.runId]?.title
                ?.takeIf(String::isNotBlank)
                ?: session.novelId.ifBlank { "未命名书卷" }
        }
    }
    val refreshState = lazySessions.loadState.refresh
    val appendState = lazySessions.loadState.append
    val initialLoading = refreshState is LoadState.Loading && lazySessions.itemCount == 0
    val isEmpty = lazySessions.itemCount == 0 &&
        refreshState is LoadState.NotLoading &&
        appendState.endOfPaginationReached
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 380.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 104.dp),
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
                    CircularProgressIndicator()
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
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Chat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("还没有聊天记录", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "选择一本已蒸馏的书，就能让人物在新的场景里开口。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            visibleSessions.isEmpty() && refreshState is LoadState.NotLoading -> item(
                span = { GridItemSpan(maxLineSpan) },
            ) {
                NoSessionMatches(onClearSearch = { onSearchQueryChange("") })
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
                sessionGroups.forEach { (bookTitle, sessions) ->
                    item(key = "book-$bookTitle", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(bookTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${sessions.size} 段会话",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(sessions, key = DialogueSessionDto::key) { session ->
                        SessionCard(
                            session = session,
                            avatarBytes = state.avatarBytes,
                            deleting = session.key in state.deletingSessionKeys,
                            selectionMode = state.selectionMode,
                            selected = session.key in state.selectedSessionKeys,
                            onOpen = { onOpenChat(session.runId, session.sessionId) },
                            onDelete = { onDelete(session) },
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
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
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
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = onRetry) { Text("重试") }
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
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SessionsSort.values().forEach { option ->
                FilterChip(
                    selected = sort == option,
                    onClick = { onSelectSort(option) },
                    enabled = enabled,
                    label = { Text(option.label) },
                )
            }
        }
    }
}

@Composable
private fun NoSessionMatches(onClearSearch: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text("没有找到相关会话", style = MaterialTheme.typography.titleMedium)
            Text(
                "可以换个书名、人物名或消息关键词。",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onClearSearch) {
                Text("清空搜索", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: DialogueSessionDto,
    avatarBytes: Map<String, ByteArray>,
    deleting: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !deleting) {
                if (selectionMode) onToggleSelection() else onOpen()
            },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.heightIn(min = 34.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.title.ifBlank { session.participants.joinToString("、") },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selectionMode && selected) {
                    Text(
                        text = "已选择",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (!selectionMode) {
                    IconButton(
                        onClick = onRename,
                        enabled = !deleting,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "修改会话标题",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        enabled = !deleting,
                        modifier = Modifier.size(34.dp),
                    ) {
                        if (deleting) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除会话",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
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
                Surface(
                    color = if (session.status == "ready") {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text(
                        text = if (session.status == "ready") "可继续" else "待处理",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${session.mode.chineseMode()} · ${session.updatedAt.toLocalDateTimeDisplay()}",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            InitialAvatar(label = "？", color = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            visibleParticipants.forEachIndexed { index, participant ->
                val color = when (index % 3) {
                    0 -> MaterialTheme.colorScheme.primaryContainer
                    1 -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.tertiaryContainer
                }
                PersonaAvatar(
                    name = participant,
                    bytes = avatarBytes["$runId|$participant"],
                    color = color,
                )
            }
            val remainingCount = participants.count(String::isNotBlank) - visibleParticipants.size
            if (remainingCount > 0) {
                InitialAvatar(label = "+$remainingCount", color = MaterialTheme.colorScheme.surfaceVariant)
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
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
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
        val bitmap = bytes?.let { decodeImageBitmap(it) }
        if (bitmap == null) {
            Box(contentAlignment = Alignment.Center) {
                Text(name.trim().take(1), style = MaterialTheme.typography.labelSmall, maxLines = 1)
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("知道了") }
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
    onCreate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.creating) onDismiss() },
        title = { Text("开始一段新会话") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Text("选择书卷", style = MaterialTheme.typography.labelLarge)
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
                    Text("你的身份", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(dialogueModeOptions, key = DialogueModeOption::value) { option ->
                            FilterChip(
                                selected = state.draft.mode == option.value,
                                onClick = { onSelectMode(option.value) },
                                enabled = !state.creating,
                                label = { Text(option.label) },
                            )
                        }
                    }
                    Text(
                        dialogueModeOptions.first { it.value == state.draft.mode }.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                item {
                    Text("出场人物", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        state.availableCharacters.forEach { character ->
                            Row(
                                modifier = Modifier
                                    .width(190.dp)
                                    .clickable(enabled = !state.creating) { onToggleParticipant(character) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = character in state.draft.participants,
                                    onCheckedChange = null,
                                    enabled = !state.creating,
                                )
                                Text(character, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                if (state.draft.mode == "act") {
                    item {
                        Text("你要扮演谁", style = MaterialTheme.typography.labelLarge)
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
                    OutlinedButton(
                        onClick = onRecommendSceneCard,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        enabled = !state.creating && !state.recommendingScene &&
                            state.draft.participants.isNotEmpty() && state.sceneCards.isNotEmpty(),
                    ) {
                        if (state.recommendingScene) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        Text(
                            if (state.recommendingScene) "正在推荐…" else "按人物与模式推荐场景",
                            modifier = Modifier.padding(start = if (state.recommendingScene) 8.dp else 0.dp),
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
                        OutlinedTextField(
                            value = state.draft.selfName,
                            onValueChange = onSelfNameChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.creating,
                            label = { Text("你的名字") },
                            placeholder = { Text("例如：沈照") },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.draft.selfIdentity,
                            onValueChange = onSelfIdentityChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.creating,
                            label = { Text("场景身份") },
                            placeholder = { Text("例如：刚到府中的远房客人") },
                            minLines = 2,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.draft.selfStyle,
                            onValueChange = onSelfStyleChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.creating,
                            label = { Text("互动风格") },
                            placeholder = { Text("例如：克制、观察为主，熟悉后更直接") },
                            minLines = 2,
                            maxLines = 3,
                        )
                    }
                }

                if (state.error.isNotBlank()) {
                    item {
                        Text(
                            text = state.error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (state.creating) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                "正在让人物进入场景，模型生成开场可能需要一会儿…",
                                modifier = Modifier.padding(start = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onCreate,
                enabled = state.canCreate && !state.creating,
            ) {
                Text(if (state.creating) "创建中" else "进入场景")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.creating) { Text("取消") }
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
    Text(label, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        ) {
            Text(
                selectedTitle?.takeIf(String::isNotBlank) ?: noneLabel,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(noneLabel) },
                onClick = {
                    expanded = false
                    onSelect("")
                },
            )
            cards.forEach { card ->
                val title = card.preview[titleKey]?.jsonPrimitive?.contentOrNull
                    ?: card.fields[titleKey]?.jsonPrimitive?.contentOrNull
                    ?: card.cardId
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        expanded = false
                        onSelect(card.cardId)
                    },
                )
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
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && runs.isNotEmpty(),
        ) {
            Text(
                text = selected?.title ?: "没有可用书卷",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            runs.forEach { run ->
                DropdownMenuItem(
                    text = { Text("${run.title} · ${run.availableCharacters.size} 人") },
                    onClick = {
                        expanded = false
                        onSelect(run.runId)
                    },
                )
            }
        }
    }
}

private fun String.chineseMode(): String = when (this) {
    "act" -> "扮演人物"
    "insert" -> "自设入场"
    else -> "旁观群聊"
}

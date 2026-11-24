package top.wkbin.zaomeng.feature.rundetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.data.api.CreateWorldBookEntryRequest
import top.wkbin.zaomeng.data.api.ManagedWorldBookDto
import top.wkbin.zaomeng.data.api.ManagedWorldBookEntryDto
import top.wkbin.zaomeng.data.api.WorldBookInjectionPosition
import top.wkbin.zaomeng.data.api.WorldBookInjectionRole
import top.wkbin.zaomeng.data.api.WorldBookKeywordMatching

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorldBooksScreen(
    viewModel: RunDetailViewModel,
    exportState: RunDetailUiState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.worldBooks.collectAsStateWithLifecycle()
    val books = state.data?.books.orEmpty()
    var activeSource by rememberSaveable { mutableStateOf("") }
    val activeBook = books.firstOrNull { it.source == activeSource } ?: books.firstOrNull()
    val activeBookSource = activeBook?.source
    LaunchedEffect(activeBookSource) {
        if (activeBookSource != null) activeSource = activeBookSource
    }
    var search by rememberSaveable { mutableStateOf("") }
    var showTest by rememberSaveable { mutableStateOf(false) }
    var selectedEntrySource by rememberSaveable { mutableStateOf("") }
    var selectedEntryIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var editingEntry by rememberSaveable { mutableStateOf(false) }
    var creatingEntrySource by rememberSaveable { mutableStateOf<String?>(null) }
    var removeBook by remember { mutableStateOf<ManagedWorldBookDto?>(null) }
    var deleteEntry by remember { mutableStateOf<WorldBookEntryDeletion?>(null) }

    val term = search.trim()
    val entries = remember(activeBook?.entries, term) {
        activeBook?.entries.orEmpty().filter { entry ->
            term.isEmpty() || entry.title.contains(term, ignoreCase = true) ||
                entry.keywords.any { it.contains(term, ignoreCase = true) } ||
                entry.secondaryKeywords.any { it.contains(term, ignoreCase = true) }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("世界书") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回书卷")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.clearWorldBookError()
                            viewModel.clearWorldBookTest()
                            showTest = true
                        },
                        enabled = !state.busy && books.any { it.entries.isNotEmpty() },
                    ) { Icon(Icons.Outlined.PlayArrow, "试触发世界书") }
                    IconButton(
                        onClick = {
                            viewModel.clearWorldBookError()
                            creatingEntrySource = activeBookSource
                        },
                        enabled = !state.busy && activeBook != null,
                    ) { Icon(Icons.Outlined.Add, "新增条目") }
                    if (activeBook != null) {
                        key(activeBook.source) {
                            WorldBookActions(
                                book = activeBook,
                                enabled = !state.busy,
                                exportEnabled = !exportState.exporting,
                                onRecursionChange = { viewModel.setWorldBookRecursion(activeBook, it) },
                                onExport = { viewModel.exportWorldBook(activeBook) },
                                onRemove = { removeBook = activeBook },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                if (state.busy || exportState.exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (books.size > 1 && activeBook != null) {
                    PrimaryTabRow(selectedTabIndex = books.indexOf(activeBook)) {
                        books.forEach { book ->
                            Tab(
                                selected = book.source == activeBook.source,
                                onClick = { activeSource = book.source },
                                text = { Text(book.scopeLabel()) },
                            )
                        }
                    }
                }
                if (activeBook != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(
                            activeBook.displayName(),
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${activeBook.scopeLabel()} · 启用 ${activeBook.entries.count { it.enabled }} / ${activeBook.entries.size} 条",
                            modifier = Modifier.padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (activeBook.entries.isNotEmpty()) {
                            OutlinedTextField(
                                value = search,
                                onValueChange = { search = it },
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                label = { Text("搜索标题或关键词") },
                                singleLine = true,
                            )
                        }
                    }
                }
                key(activeBookSource, term) {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (state.error.isNotBlank()) {
                            item(key = "load-error") {
                                Text(state.error, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = viewModel::loadWorldBooks, enabled = !state.busy) { Text("重新加载") }
                            }
                        }
                        if (exportState.error.isNotBlank() || exportState.message.isNotBlank()) {
                            item(key = "export-notice") {
                                NoticeCard(
                                    message = exportState.error.ifBlank { exportState.message },
                                    error = exportState.error.isNotBlank(),
                                    onDismiss = viewModel::dismissNotice,
                                    actionLabel = if (exportState.exportedPackage != null && exportState.error.isNotBlank()) {
                                        "重新选择位置"
                                    } else "",
                                    onAction = viewModel::retryExportDestination,
                                )
                            }
                        }
                        if (state.data == null && state.error.isBlank()) {
                            item { Text("正在加载世界书…") }
                        } else if (state.data != null && activeBook == null) {
                            item { Text("这个书卷没有挂载世界书。可从「导入书卷」添加独立世界书。") }
                        }
                        if (activeBook != null) {
                            if (entries.isEmpty()) {
                                item { Text(if (term.isEmpty()) "这本世界书还没有条目。" else "没有匹配的条目。") }
                            }
                            items(entries, key = { "${activeBook.source}/${it.index}" }) { entry ->
                                WorldBookEntryRow(
                                    entry = entry,
                                    enabled = !state.busy,
                                    onOpen = {
                                        viewModel.clearWorldBookError()
                                        selectedEntrySource = activeBook.source
                                        selectedEntryIndex = entry.index
                                    },
                                    onToggle = { viewModel.setWorldBookEntry(activeBook, entry.index, it) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTest) {
        WorldBookTestDialog(viewModel, state, onClose = {
            showTest = false
            viewModel.clearWorldBookTest()
            viewModel.clearWorldBookError()
        })
    }

    val selectedBook = books.firstOrNull { it.source == selectedEntrySource }
    val entry = selectedBook?.entries?.getOrNull(selectedEntryIndex ?: -1)
    val createIn = books.firstOrNull { it.source == creatingEntrySource }
    val deletion = deleteEntry
    if (deletion != null) {
        val cancel: () -> Unit = {
            deleteEntry = null
            if (state.error.isNotBlank()) selectedEntryIndex = null
        }
        AlertDialog(
            onDismissRequest = { if (!state.busy) cancel() },
            title = { Text("删除世界书条目？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将从「${deletion.bookName}」删除「${deletion.title}」。删除后无法撤销。")
                    if (state.error.isNotBlank()) {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteWorldBookEntry(deletion.source, deletion.index, deletion.revision) {
                            selectedEntryIndex = null
                            deleteEntry = null
                        }
                    },
                    enabled = !state.busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(if (state.busy) "删除中…" else "删除") }
            },
            dismissButton = { TextButton(onClick = cancel, enabled = !state.busy) { Text("取消") } },
        )
    } else if (createIn != null) {
        WorldBookEntryEditor(
            book = createIn,
            entry = null,
            saving = state.busy,
            error = state.error,
            onCancel = { creatingEntrySource = null },
            onSave = { draft ->
                val request = CreateWorldBookEntryRequest(
                    revision = draft.revision,
                    content = draft.content.orEmpty(),
                    keywords = draft.keywords.orEmpty(),
                    comment = draft.comment.orEmpty(),
                    secondaryKeywords = draft.secondaryKeywords.orEmpty(),
                    constant = draft.constant == true,
                    placement = draft.placement,
                    activationDelay = draft.activationDelay,
                    sticky = draft.sticky,
                    cooldown = draft.cooldown,
                    group = draft.group,
                    triggers = draft.triggers,
                    matching = draft.matching,
                    ignoreBudget = draft.ignoreBudget,
                )
                viewModel.createWorldBookEntry(createIn.source, request) {
                    creatingEntrySource = null
                    selectedEntrySource = createIn.source
                    selectedEntryIndex = createIn.entries.size
                }
            },
        )
    } else if (selectedBook != null && entry != null && editingEntry) {
        WorldBookEntryEditor(
            book = selectedBook,
            entry = entry,
            saving = state.busy,
            error = state.error,
            onCancel = { editingEntry = false },
            onSave = { request ->
                viewModel.editWorldBookEntry(selectedBook.source, entry.index, request) { editingEntry = false }
            },
        )
    } else if (selectedBook != null && entry != null) {
        AlertDialog(
            onDismissRequest = { selectedEntryIndex = null },
            title = { Text(entry.title) },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (entry.constant) item { Text("常驻条目") }
                    if (entry.ignoreBudget) item { Text("忽略普通条目预算") }
                    if (entry.activationDelay > 0) item { Text("达到 ${entry.activationDelay} 条消息后可触发") }
                    if (entry.sticky > 0) item { Text("粘滞 ${entry.sticky} 条消息") }
                    if (entry.cooldown > 0) item { Text("冷却 ${entry.cooldown} 条消息") }
                    if (entry.triggers.isNotEmpty() || entry.hasUnknownTriggers) {
                        val labels = entry.triggers.map { it.generationLabel() } +
                            listOf("未知类型").filter { entry.hasUnknownTriggers }
                        item { Text("生成类型 · ${labels.joinToString("、")}") }
                    }
                    entry.group?.takeIf { it.names.isNotBlank() }?.let { group ->
                        item { Text("互斥分组 · ${group.names}") }
                    }
                    entry.position?.let { position -> item { Text(position.label(entry.injectionDepth, entry.injectionRole, entry.outletName)) } }
                    if (entry.keywords.isNotEmpty()) item { Text("主关键词：${entry.keywords.joinToString("、")}") }
                    if (entry.secondaryKeywords.isNotEmpty()) item {
                        Text("次关键词：${entry.secondaryKeywords.joinToString("、")}")
                        Text(if (entry.matching.useSecondary) entry.matching.secondaryLogic.label() else "次关键词未启用")
                    }
                    entry.matching.scanDepth?.let {
                        item { Text("扫描最近 ${it.coerceAtMost(WorldBookKeywordMatching.MAX_SCAN_DEPTH)} 条消息") }
                    }
                    if (entry.matching.sources.isNotEmpty()) item {
                        Text("额外扫描 · ${entry.matching.sources.joinToString("、") { it.label() }}")
                    }
                    items(entry.content.chunked(2000)) { Text(it) }
                    item {
                        TextButton(
                            onClick = {
                                viewModel.clearWorldBookError()
                                deleteEntry = WorldBookEntryDeletion(
                                    source = selectedBook.source,
                                    index = entry.index,
                                    revision = selectedBook.revision,
                                    bookName = selectedBook.displayName(),
                                    title = entry.title,
                                )
                            },
                            enabled = !state.busy,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("删除条目") }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    viewModel.clearWorldBookError()
                    editingEntry = true
                }) { Text("编辑") }
            },
            dismissButton = { TextButton(onClick = { selectedEntryIndex = null }) { Text("返回条目列表") } },
        )
    }
    removeBook?.let { book ->
        AlertDialog(
            onDismissRequest = { removeBook = null },
            title = { Text("移除独立世界书？") },
            text = { Text("「${book.displayName()}」的 ${book.entries.size} 条设定将从这个书卷解绑。") },
            confirmButton = {
                TextButton(onClick = {
                    removeBook = null
                    viewModel.removeWorldBook(book)
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removeBook = null }) { Text("取消") } },
        )
    }
}

private data class WorldBookEntryDeletion(
    val source: String,
    val index: Int,
    val revision: String,
    val bookName: String,
    val title: String,
)

internal fun WorldBookInjectionPosition.label(depth: Int?, role: WorldBookInjectionRole?, outlet: String?): String = when (this) {
    WorldBookInjectionPosition.BeforeCharacter -> "注入到角色定义前"
    WorldBookInjectionPosition.AfterCharacter -> "注入到角色定义后"
    WorldBookInjectionPosition.BeforeAuthorNote -> "注入到作者注释前"
    WorldBookInjectionPosition.AfterAuthorNote -> "注入到作者注释后"
    WorldBookInjectionPosition.BeforeExamples -> "注入到对话示例前"
    WorldBookInjectionPosition.AfterExamples -> "注入到对话示例后"
    WorldBookInjectionPosition.Outlet -> "命名出口 · ${outlet ?: "缺少名称"}"
    WorldBookInjectionPosition.AtDepth -> {
        val roleLabel = (role ?: WorldBookInjectionRole.System).roleLabel()
        "聊天深度 ${depth ?: 4} · $roleLabel"
    }
}

private fun ManagedWorldBookDto.displayName(): String = name.ifBlank {
    if (source == "character_book") "卡内世界书" else "独立世界书"
}

@Composable
private fun WorldBookEntryRow(
    entry: ManagedWorldBookEntryDto,
    enabled: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.weight(1f).heightIn(min = 56.dp).clickable(enabled = enabled, onClick = onOpen)
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (entry.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = when {
                entry.constant -> "常驻"
                entry.keywords.isNotEmpty() -> entry.keywords.joinToString("、")
                else -> "无主关键词"
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = entry.enabled,
            onCheckedChange = onToggle,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "启用 ${entry.title}" },
        )
    }
}

@Composable
private fun WorldBookActions(
    book: ManagedWorldBookDto,
    enabled: Boolean,
    exportEnabled: Boolean,
    onRecursionChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }, enabled = enabled) {
            Icon(Icons.Filled.MoreVert, "更多世界书操作")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (book.recursionEnabled) "关闭递归触发" else "开启递归触发") },
                onClick = {
                    menuOpen = false
                    onRecursionChange(!book.recursionEnabled)
                },
            )
            DropdownMenuItem(
                text = { Text("导出世界书 JSON") },
                enabled = exportEnabled,
                onClick = {
                    menuOpen = false
                    onExport()
                },
            )
            if (book.source == "world_book") {
                DropdownMenuItem(
                    text = { Text("移除独立世界书") },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
    }
}

private fun ManagedWorldBookDto.scopeLabel(): String =
    if (source == "character_book") "卡内世界书" else "独立世界书"

package top.wkbin.zaomeng.feature.rundetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.EmptyStateSizeType
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.TabRow
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.icon.extended.Play
import io.github.suqi8.coui.kmp.overlay.OverlayListPopup
import io.github.suqi8.coui.kmp.preference.SwitchWithDividerPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.data.api.CreateWorldBookEntryRequest
import top.wkbin.zaomeng.data.api.ManagedWorldBookDto
import top.wkbin.zaomeng.data.api.ManagedWorldBookEntryDto
import top.wkbin.zaomeng.data.api.WorldBookInjectionPosition
import top.wkbin.zaomeng.data.api.WorldBookInjectionRole
import top.wkbin.zaomeng.data.api.WorldBookKeywordMatching
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiPageScaffold
import top.wkbin.zaomeng.ui.coui.CouiTextField
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.couiPageScroll
import top.wkbin.zaomeng.ui.coui.isWideWindow

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

    CouiPageScaffold(
        modifier = modifier,
        title = "世界书",
        topBarStyle = CouiTopBarStyle.Small,
        onBack = onClose,
        wide = isWideWindow(),
        actions = {
            IconButton(
                onClick = {
                    viewModel.clearWorldBookError()
                    viewModel.clearWorldBookTest()
                    showTest = true
                },
                enabled = !state.busy && books.any { it.entries.isNotEmpty() },
            ) { Icon(COUIIcons.Play, "试触发世界书") }
            IconButton(
                onClick = {
                    viewModel.clearWorldBookError()
                    creatingEntrySource = activeBookSource
                },
                enabled = !state.busy && activeBook != null,
            ) { Icon(COUIIcons.Add, "新增条目") }
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
    ) { scope ->
        Box(Modifier.fillMaxSize().padding(scope.contentPadding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                if (state.busy || exportState.exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (books.size > 1 && activeBook != null) {
                    TabRow(
                        tabs = books.map { it.scopeLabel() },
                        selectedTabIndex = books.indexOf(activeBook),
                        onTabSelected = { activeSource = books[it].source },
                    )
                }
                if (activeBook != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(
                            activeBook.displayName(),
                            style = COUITheme.textStyles.displayXS,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${activeBook.scopeLabel()} · 启用 ${activeBook.entries.count { it.enabled }} / ${activeBook.entries.size} 条",
                            modifier = Modifier.padding(top = 4.dp),
                            color = COUITheme.colorScheme.labelSecondary,
                            style = COUITheme.textStyles.bodyM,
                        )
                        if (activeBook.entries.isNotEmpty()) {
                            CouiTextField(
                                value = search,
                                onValueChange = { search = it },
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                label = "搜索标题或关键词",
                            )
                        }
                    }
                }
                key(activeBookSource, term) {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        modifier = Modifier.fillMaxSize().couiPageScroll(scope),
                    ) {
                        if (state.error.isNotBlank()) {
                            item(key = "load-error") {
                                Text(state.error, color = COUITheme.colorScheme.error)
                                TextButton(
                                    text = "重新加载",
                                    onClick = viewModel::loadWorldBooks,
                                    enabled = !state.busy,
                                    minWidth = 0.dp,
                                )
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
                            item {
                                EmptyState(
                                    title = "这个书卷没有挂载世界书。",
                                    subtitle = "可从「导入书卷」添加独立世界书。",
                                    sizeType = EmptyStateSizeType.Small,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                )
                            }
                        }
                        if (activeBook != null) {
                            if (entries.isEmpty()) {
                                item {
                                    EmptyState(
                                        title = if (term.isEmpty()) "这本世界书还没有条目。" else "没有匹配的条目。",
                                        sizeType = EmptyStateSizeType.Small,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                    )
                                }
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
            CouiAlertDialog(
                onDismissRequest = { if (!state.busy) cancel() },
                title = "删除世界书条目？",
                confirmText = if (state.busy) "删除中…" else "删除",
                onConfirm = {
                    viewModel.deleteWorldBookEntry(deletion.source, deletion.index, deletion.revision) {
                        selectedEntryIndex = null
                        deleteEntry = null
                    }
                },
                confirmEnabled = !state.busy,
                dismissText = "取消",
                onDismiss = { if (!state.busy) cancel() },
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("将从「${deletion.bookName}」删除「${deletion.title}」。删除后无法撤销。")
                        if (state.error.isNotBlank()) {
                            Text(state.error, color = COUITheme.colorScheme.error)
                        }
                    }
                },
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
            CouiAlertDialog(
                onDismissRequest = { selectedEntryIndex = null },
                title = entry.title,
                confirmText = "编辑",
                onConfirm = {
                    viewModel.clearWorldBookError()
                    editingEntry = true
                },
                confirmEnabled = !state.busy,
                dismissText = "返回条目列表",
                onDismiss = { selectedEntryIndex = null },
                body = {
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
                                text = "删除条目",
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
                                minWidth = 0.dp,
                                colors = ButtonDefaults.textButtonColors(textColor = COUITheme.colorScheme.error),
                            )
                        }
                    }
                },
            )
        }
        removeBook?.let { book ->
            CouiAlertDialog(
                onDismissRequest = { removeBook = null },
                title = "移除独立世界书？",
                confirmText = "移除",
                onConfirm = {
                    removeBook = null
                    viewModel.removeWorldBook(book)
                },
                dismissText = "取消",
                onDismiss = { removeBook = null },
                body = {
                    Text("「${book.displayName()}」的 ${book.entries.size} 条设定将从这个书卷解绑。")
                },
            )
        }
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
    val subtitle = when {
        entry.constant -> "常驻"
        entry.keywords.isNotEmpty() -> entry.keywords.joinToString("、")
        else -> "无主关键词"
    }
    SwitchWithDividerPreference(
        checked = entry.enabled,
        onCheckedChange = onToggle,
        title = entry.title,
        modifier = Modifier.fillMaxWidth(),
        onClick = if (enabled) onOpen else null,
        titleColor = BasicComponentDefaults.titleColor(
            color = if (entry.enabled) COUITheme.colorScheme.labelPrimary else COUITheme.colorScheme.labelSecondary,
        ),
        summary = subtitle,
        enabled = enabled,
        insideMargin = PaddingValues(horizontal = 0.dp, vertical = 10.dp),
    )
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
            Icon(COUIIcons.More, "更多世界书操作")
        }
        OverlayListPopup(show = menuOpen, onDismissRequest = { menuOpen = false }) {
            ListPopupColumn {
                val optionSize = if (book.source == "world_book") 3 else 2
                DropdownImpl(
                    text = if (book.recursionEnabled) "关闭递归触发" else "开启递归触发",
                    optionSize = optionSize,
                    isSelected = false,
                    index = 0,
                    onSelectedIndexChange = {
                        menuOpen = false
                        onRecursionChange(!book.recursionEnabled)
                    },
                )
                DropdownImpl(
                    text = "导出世界书 JSON",
                    optionSize = optionSize,
                    isSelected = false,
                    index = 1,
                    enabled = exportEnabled,
                    onSelectedIndexChange = {
                        menuOpen = false
                        onExport()
                    },
                )
                if (book.source == "world_book") {
                    DropdownImpl(
                        text = "移除独立世界书",
                        optionSize = optionSize,
                        isSelected = false,
                        index = 2,
                        onSelectedIndexChange = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

private fun ManagedWorldBookDto.scopeLabel(): String =
    if (source == "character_book") "卡内世界书" else "独立世界书"

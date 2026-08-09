package top.wkbin.zaomeng.feature.chapters

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.data.api.ChapterDto
import top.wkbin.zaomeng.platform.rememberFileExporter
import top.wkbin.zaomeng.platform.rememberNotificationPermissionRequester
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersScreen(
    viewModel: ChaptersViewModel,
    onBack: () -> Unit,
    onOpenChat: (String, String) -> Unit,
    onOpenPersona: (String, String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ChapterDto?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var showArchive by remember { mutableStateOf(false) }
    var pendingNovelConversion by remember { mutableStateOf<Pair<String, String>?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val requestNotificationPermission = rememberNotificationPermissionRequester { granted ->
        pendingNovelConversion?.let { (sessionId, title) ->
            if (granted) {
                viewModel.convertSession(sessionId, title)
                showArchive = false
            } else {
                showArchive = false
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("未授予通知权限，无法在后台生成完成后提醒你。")
                }
            }
            pendingNovelConversion = null
        }
    }
    var launchedRequestId by remember { mutableStateOf(0L) }
    val fileExporter = rememberFileExporter(
        onSave = { sink -> viewModel.saveExport(sink) },
        onCancelled = { viewModel.discardExport() },
    )

    LaunchedEffect(state.exportRequestId, state.exported?.filename) {
        val exported = state.exported ?: return@LaunchedEffect
        if (state.exportRequestId != launchedRequestId) {
            launchedRequestId = state.exportRequestId
            fileExporter(
                exported.filename,
                if (exported.filename.endsWith(".md")) "text/markdown" else "text/plain",
            )
        }
    }
    LaunchedEffect(state.navigationSessionId) {
        val sessionId = state.navigationSessionId
        if (sessionId.isNotBlank()) {
            viewModel.consumeNavigationSession()
            onOpenChat(viewModel.runId, sessionId)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("章节工作台") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { showArchive = true }, enabled = state.sessions.isNotEmpty() && !state.saving) {
                        Icon(Icons.Default.Forum, "转为小说")
                    }
                    IconButton(onClick = { viewModel.export("markdown") }, enabled = !state.exporting) {
                        Icon(Icons.Default.FileDownload, "导出 Markdown")
                    }
                    IconButton(onClick = { showCreate = true }, enabled = !state.saving) {
                        Icon(Icons.Default.Add, "新建章节")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                    Text("把会话沉淀成章节草稿，再统一导出全书。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = viewModel::updateSearchQuery,
                            label = { Text("搜索章节和会话") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        )
                        Button(
                            onClick = viewModel::search,
                            enabled = !state.searching && state.searchQuery.isNotBlank(),
                        ) {
                            Text(if (state.searching) "…" else "搜索")
                        }
                    }
                    val suggestions = remember(state.chapters, state.sessions) {
                        chapterSearchSuggestions(state.chapters, state.sessions)
                    }
                    if (state.searchQuery.isBlank() && suggestions.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(suggestions, key = { it }) { character ->
                                AssistChip(
                                    onClick = { viewModel.searchFor(character) },
                                    label = { Text(character) },
                                )
                            }
                        }
                    }
                    }
                }
            }
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("问书卷", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = state.bookQuestion,
                            onValueChange = viewModel::updateBookQuestion,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("例如：宝玉和黛玉最近一次冲突是什么？") },
                            minLines = 2,
                            maxLines = 4,
                        )
                        Button(
                            onClick = viewModel::askBook,
                            enabled = state.bookQuestion.isNotBlank() && !state.askingBook,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.askingBook) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("根据书卷证据回答")
                        }
                    }
                }
            }
            state.bookAnswer?.let { answer ->
                item {
                    Card {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("回答", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(answer.answer)
                            if (answer.evidence.isNotEmpty()) {
                                Text("引用证据", style = MaterialTheme.typography.labelLarge)
                                answer.evidence.forEach { evidence ->
                                    Text("${searchResultKindLabel(evidence.kind)} · ${evidence.title}", style = MaterialTheme.typography.labelMedium)
                                    Text(evidence.preview, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    if (evidence.kind == "persona" && evidence.character.isNotBlank()) {
                                        TextButton(onClick = { onOpenPersona(viewModel.runId, evidence.character) }) {
                                            Text("打开人物档案")
                                        }
                                    }
                                    if (evidence.kind == "session" && evidence.sessionId.isNotBlank()) {
                                        TextButton(onClick = { onOpenChat(viewModel.runId, evidence.sessionId) }) {
                                            Text("打开原始会话")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.error.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        state.error,
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (state.message.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(
                        state.message,
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            if (state.searchResults.isNotEmpty()) {
                item {
                    val kinds = state.searchResults.groupingBy { it.kind }.eachCount()
                    Text(
                        "搜索结果 · 章节 ${kinds["chapter"] ?: 0} · 人物 ${kinds["persona"] ?: 0} · 会话 ${kinds["session"] ?: 0}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(state.searchResults, key = { "${it.kind}:${it.chapterId}:${it.sessionId}:${it.character}" }) { result ->
                    Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(searchResultKindLabel(result.kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(if (result.kind == "session") "会话 · ${result.title}" else result.title, fontWeight = FontWeight.SemiBold)
                        Text(result.preview, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (result.kind == "persona" && result.character.isNotBlank()) {
                            TextButton(onClick = { onOpenPersona(viewModel.runId, result.character) }) { Text("校对人物") }
                        }
                        if (result.kind == "session" && result.sessionId.isNotBlank()) {
                            TextButton(onClick = { onOpenChat(viewModel.runId, result.sessionId) }) { Text("打开会话") }
                        }
                    } }
                }
            }
            if (!state.searching && state.searchQuery.isNotBlank() && state.searchResults.isEmpty()) item {
                Text("没有找到相关证据。可换一个角色名、章节标题或会话中的关键词再试。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.loading) item { CircularProgressIndicator() }
            if (!state.loading && state.chapters.isEmpty()) item {
                Card { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("还没有章节", fontWeight = FontWeight.SemiBold)
                    Text("新建一个空白章节，或把现有聊天会话转为小说草稿。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showCreate = true }) { Text("新建章节") }
                        OutlinedButton(onClick = { showArchive = true }, enabled = state.sessions.isNotEmpty()) { Text("转为小说") }
                    }
                } }
            }
            items(state.chapters, key = ChapterDto::chapterId) { chapter ->
                ChapterCard(
                    chapter = chapter,
                    opening = state.openingChapterId == chapter.chapterId,
                    syncing = state.syncingChapterId == chapter.chapterId,
                    canMoveUp = chapter.order > 1,
                    canMoveDown = chapter.order < state.chapters.size,
                    onContinue = { viewModel.continueWriting(chapter.chapterId) },
                    onSync = { viewModel.syncLatestSession(chapter.chapterId) },
                    onMoveUp = { viewModel.move(chapter.chapterId, chapter.order - 1) },
                    onMoveDown = { viewModel.move(chapter.chapterId, chapter.order + 1) },
                    onEdit = { editing = chapter },
                    onDelete = { viewModel.delete(chapter.chapterId) },
                )
            }
            if (state.chapters.isNotEmpty()) item {
                OutlinedButton(onClick = { viewModel.export("text") }, enabled = !state.exporting, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.exporting) "正在准备导出…" else "导出全书 TXT")
                }
            }
        }
    }
    if (showCreate) ChapterEditorDialog(onDismiss = { showCreate = false }) { title, goal, participants, content ->
        viewModel.save(title = title, goal = goal, participants = participants, content = content)
        showCreate = false
    }
    editing?.let { chapter -> ChapterEditorDialog(chapter = chapter, onDismiss = { editing = null }) { title, goal, participants, content ->
        viewModel.save(chapter.chapterId, title, goal, participants, content)
        editing = null
    } }
    if (showArchive) ArchiveSessionDialog(
        sessions = state.sessions,
        onDismiss = { showArchive = false },
        onArchive = { sessionId, title ->
            if (viewModel.hasNotificationPermission()) {
                viewModel.convertSession(sessionId, title)
                showArchive = false
            } else {
                pendingNovelConversion = sessionId to title
                requestNotificationPermission()
            }
        },
    )
}

private fun searchResultKindLabel(kind: String): String = when (kind) {
    "chapter" -> "章节草稿"
    "persona" -> "人物档案"
    "session" -> "会话记录"
    else -> "书卷内容"
}

@Composable
private fun ChapterCard(
    chapter: ChapterDto,
    opening: Boolean,
    syncing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onContinue: () -> Unit,
    onSync: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("第 ${chapter.order} 章 · ${chapter.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (chapter.goal.isNotBlank()) Text("目标：${chapter.goal}", style = MaterialTheme.typography.bodySmall)
        if (chapter.participants.isNotEmpty()) Text(chapter.participants.joinToString("、"), style = MaterialTheme.typography.labelSmall)
        Text(chapter.content.ifBlank { "空白草稿" }, maxLines = 5, overflow = TextOverflow.Ellipsis)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(onClick = onContinue, enabled = !opening) { Text(if (opening) "正在入场…" else "继续写作") }
            if (chapter.lastSessionId.isNotBlank()) {
                OutlinedButton(onClick = onSync, enabled = !syncing) { Text(if (syncing) "正在收录…" else "收录会话") }
            }
            TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("上移") }
            TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("下移") }
            OutlinedButton(onClick = onEdit) { Text("编辑") }
            TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    } }
}

@Composable
private fun ChapterEditorDialog(chapter: ChapterDto? = null, onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var title by remember(chapter) { mutableStateOf(chapter?.title.orEmpty()) }
    var goal by remember(chapter) { mutableStateOf(chapter?.goal.orEmpty()) }
    var participants by remember(chapter) { mutableStateOf(chapter?.participants?.joinToString("、").orEmpty()) }
    var content by remember(chapter) { mutableStateOf(chapter?.content.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (chapter == null) "新建章节" else "编辑章节") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            OutlinedTextField(title, { title = it }, label = { Text("章节标题") }, singleLine = true)
            OutlinedTextField(goal, { goal = it }, label = { Text("本章目标") })
            OutlinedTextField(participants, { participants = it }, label = { Text("出场人物（用顿号或逗号分隔）") })
            OutlinedTextField(content, { content = it }, label = { Text("草稿内容") }, minLines = 6)
            }
        },
        confirmButton = { Button(onClick = { onSave(title, goal, participants, content) }, enabled = title.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ArchiveSessionDialog(sessions: List<top.wkbin.zaomeng.data.api.DialogueSessionDto>, onDismiss: () -> Unit, onArchive: (String, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("转为小说章节") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("章节标题（可选）") },
                    singleLine = true,
                )
            }
            item {
                Text(
                    "至少需要 6 轮有效对话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sessions.isEmpty()) {
                item { Text("还没有可归档的会话。") }
            }
            items(sessions, key = { it.sessionId }) { session ->
                OutlinedButton(onClick = { onArchive(session.sessionId, title) }, modifier = Modifier.fillMaxWidth()) {
                    Text("转为小说 · " + session.lastEntryPreview.ifBlank { "会话 ${session.sessionId.takeLast(6)}" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

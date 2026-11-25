package top.wkbin.zaomeng.feature.chapters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.EmptyStateSizeType
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.SnackbarHostState
import io.github.suqi8.coui.kmp.basic.SuggestionChip
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.Messages
import io.github.suqi8.coui.kmp.icon.extended.Search
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.api.ChapterDto
import top.wkbin.zaomeng.platform.rememberFileExporter
import top.wkbin.zaomeng.platform.rememberNotificationPermissionRequester
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiPageScaffold
import top.wkbin.zaomeng.ui.coui.CouiTextField
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.couiPageScroll
import top.wkbin.zaomeng.ui.coui.isWideWindow

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

    CouiPageScaffold(
        title = "章节工作台",
        topBarStyle = CouiTopBarStyle.Small,
        onBack = onBack,
        wide = isWideWindow(),
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(
                onClick = { showArchive = true },
                enabled = state.sessions.isNotEmpty() && !state.saving,
            ) {
                Icon(COUIIcons.Messages, "转为小说")
            }
            IconButton(
                onClick = { viewModel.export("markdown") },
                enabled = !state.exporting,
            ) {
                Icon(COUIIcons.Download, "导出 Markdown")
            }
            IconButton(onClick = { showCreate = true }, enabled = !state.saving) {
                Icon(COUIIcons.Add, "新建章节")
            }
        },
    ) { scope ->
        LazyColumn(
            modifier = Modifier.couiPageScroll(scope).padding(horizontal = 16.dp),
            contentPadding = scope.contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "把会话沉淀成章节草稿，再统一导出全书。",
                            style = COUITheme.textStyles.bodyM,
                            color = COUITheme.colorScheme.labelSecondary,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CouiTextField(
                                value = state.searchQuery,
                                onValueChange = viewModel::updateSearchQuery,
                                label = "搜索章节和会话",
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                leadingIcon = { Icon(COUIIcons.Search, contentDescription = null) },
                            )
                            Button(
                                onClick = viewModel::search,
                                enabled = !state.searching && state.searchQuery.isNotBlank(),
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                minWidth = 0.dp,
                            ) {
                                Text(if (state.searching) "…" else "搜索", style = COUITheme.textStyles.buttonM)
                            }
                        }
                        val suggestions = remember(state.chapters, state.sessions) {
                            chapterSearchSuggestions(state.chapters, state.sessions)
                        }
                        if (state.searchQuery.isBlank() && suggestions.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(suggestions, key = { it }) { character ->
                                    SuggestionChip(
                                        onClick = { viewModel.searchFor(character) },
                                        label = character,
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
                        Text("问书卷", style = COUITheme.textStyles.headlineXS, fontWeight = FontWeight.SemiBold)
                        CouiTextField(
                            value = state.bookQuestion,
                            onValueChange = viewModel::updateBookQuestion,
                            modifier = Modifier.fillMaxWidth(),
                            label = "例如：宝玉和黛玉最近一次冲突是什么？",
                            minLines = 2,
                            maxLines = 4,
                        )
                        Button(
                            onClick = viewModel::askBook,
                            enabled = state.bookQuestion.isNotBlank() && !state.askingBook,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            if (state.askingBook) {
                                CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                            } else {
                                Text("根据书卷证据回答", style = COUITheme.textStyles.buttonM)
                            }
                        }
                    }
                }
            }
            state.bookAnswer?.let { answer ->
                item {
                    Card {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("回答", style = COUITheme.textStyles.headlineXS, fontWeight = FontWeight.SemiBold)
                            Text(answer.answer, style = COUITheme.textStyles.bodyM)
                            if (answer.evidence.isNotEmpty()) {
                                Text("引用证据", style = COUITheme.textStyles.bodyM, fontWeight = FontWeight.Medium)
                                answer.evidence.forEach { evidence ->
                                    Text(
                                        "${searchResultKindLabel(evidence.kind)} · ${evidence.title}",
                                        style = COUITheme.textStyles.bodyXS,
                                    )
                                    Text(
                                        evidence.preview,
                                        style = COUITheme.textStyles.bodyXS,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (evidence.kind == "persona" && evidence.character.isNotBlank()) {
                                        TextButton(
                                            text = "打开人物档案",
                                            onClick = { onOpenPersona(viewModel.runId, evidence.character) },
                                            minWidth = 0.dp,
                                            textStyle = COUITheme.textStyles.bodyM,
                                        )
                                    }
                                    if (evidence.kind == "session" && evidence.sessionId.isNotBlank()) {
                                        TextButton(
                                            text = "打开原始会话",
                                            onClick = { onOpenChat(viewModel.runId, evidence.sessionId) },
                                            minWidth = 0.dp,
                                            textStyle = COUITheme.textStyles.bodyM,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.error.isNotBlank()) item {
                Card(
                    colors = CardDefaults.defaultColors(
                        color = COUITheme.colorScheme.error,
                        contentColor = COUITheme.colorScheme.onError,
                    ),
                ) {
                    Text(
                        state.error,
                        modifier = Modifier.padding(14.dp),
                        color = COUITheme.colorScheme.onError,
                        style = COUITheme.textStyles.bodyM,
                    )
                }
            }
            if (state.message.isNotBlank()) item {
                Card(
                    colors = CardDefaults.defaultColors(
                        color = COUITheme.colorScheme.secondaryContainer,
                        contentColor = COUITheme.colorScheme.labelPrimary,
                    ),
                ) {
                    Text(
                        state.message,
                        modifier = Modifier.padding(14.dp),
                        color = COUITheme.colorScheme.labelPrimary,
                        style = COUITheme.textStyles.bodyM,
                    )
                }
            }
            if (state.searchResults.isNotEmpty()) {
                item {
                    val kinds = state.searchResults.groupingBy { it.kind }.eachCount()
                    Text(
                        "搜索结果 · 章节 ${kinds["chapter"] ?: 0} · 人物 ${kinds["persona"] ?: 0} · 会话 ${kinds["session"] ?: 0}",
                        style = COUITheme.textStyles.headlineXS,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(state.searchResults, key = { "${it.kind}:${it.chapterId}:${it.sessionId}:${it.character}" }) { result ->
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                searchResultKindLabel(result.kind),
                                style = COUITheme.textStyles.bodyXS,
                                color = COUITheme.colorScheme.primary,
                            )
                            Text(
                                if (result.kind == "session") "会话 · ${result.title}" else result.title,
                                style = COUITheme.textStyles.bodyM,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(result.preview, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (result.kind == "persona" && result.character.isNotBlank()) {
                                TextButton(
                                    text = "校对人物",
                                    onClick = { onOpenPersona(viewModel.runId, result.character) },
                                    minWidth = 0.dp,
                                    textStyle = COUITheme.textStyles.bodyM,
                                )
                            }
                            if (result.kind == "session" && result.sessionId.isNotBlank()) {
                                TextButton(
                                    text = "打开会话",
                                    onClick = { onOpenChat(viewModel.runId, result.sessionId) },
                                    minWidth = 0.dp,
                                    textStyle = COUITheme.textStyles.bodyM,
                                )
                            }
                        }
                    }
                }
            }
            if (!state.searching && state.searchQuery.isNotBlank() && state.searchResults.isEmpty()) item {
                EmptyState(
                    title = "没有找到相关证据。",
                    subtitle = "可换一个角色名、章节标题或会话中的关键词再试。",
                    sizeType = EmptyStateSizeType.Small,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }
            if (state.loading) item { CircularProgressIndicator() }
            if (!state.loading && state.chapters.isEmpty()) item {
                Card {
                    EmptyState(
                        title = "还没有章节",
                        subtitle = "新建一个空白章节，或把现有聊天会话转为小说草稿。",
                        actionText = "新建章节",
                        onActionClick = { showCreate = true },
                        sizeType = EmptyStateSizeType.Small,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
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
                Button(
                    onClick = { viewModel.export("text") },
                    enabled = !state.exporting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.exporting) "正在准备导出…" else "导出全书 TXT",
                        style = COUITheme.textStyles.buttonM,
                    )
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
        Text(
            "第 ${chapter.order} 章 · ${chapter.title}",
            style = COUITheme.textStyles.headlineXS,
            fontWeight = FontWeight.SemiBold,
        )
        if (chapter.goal.isNotBlank()) {
            Text("目标：${chapter.goal}", style = COUITheme.textStyles.bodyXS)
        }
        if (chapter.participants.isNotEmpty()) {
            Text(chapter.participants.joinToString("、"), style = COUITheme.textStyles.bodyXS)
        }
        Text(chapter.content.ifBlank { "空白草稿" }, maxLines = 5, overflow = TextOverflow.Ellipsis)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = onContinue,
                enabled = !opening,
                colors = ButtonDefaults.buttonColorsPrimary(),
                minWidth = 0.dp,
            ) {
                Text(if (opening) "正在入场…" else "继续写作", style = COUITheme.textStyles.buttonM)
            }
            if (chapter.lastSessionId.isNotBlank()) {
                Button(onClick = onSync, enabled = !syncing, minWidth = 0.dp) {
                    Text(if (syncing) "正在收录…" else "收录会话", style = COUITheme.textStyles.buttonM)
                }
            }
            TextButton(
                text = "上移",
                onClick = onMoveUp,
                enabled = canMoveUp,
                minWidth = 0.dp,
                textStyle = COUITheme.textStyles.bodyM,
            )
            TextButton(
                text = "下移",
                onClick = onMoveDown,
                enabled = canMoveDown,
                minWidth = 0.dp,
                textStyle = COUITheme.textStyles.bodyM,
            )
            Button(onClick = onEdit, minWidth = 0.dp) {
                Text("编辑", style = COUITheme.textStyles.buttonM)
            }
            TextButton(
                text = "删除",
                onClick = onDelete,
                minWidth = 0.dp,
                textStyle = COUITheme.textStyles.bodyM,
                colors = ButtonDefaults.textButtonColors(textColor = COUITheme.colorScheme.error),
            )
        }
    } }
}

@Composable
private fun ChapterEditorDialog(chapter: ChapterDto? = null, onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var title by remember(chapter) { mutableStateOf(chapter?.title.orEmpty()) }
    var goal by remember(chapter) { mutableStateOf(chapter?.goal.orEmpty()) }
    var participants by remember(chapter) { mutableStateOf(chapter?.participants?.joinToString("、").orEmpty()) }
    var content by remember(chapter) { mutableStateOf(chapter?.content.orEmpty()) }
    CouiAlertDialog(
        onDismissRequest = onDismiss,
        title = if (chapter == null) "新建章节" else "编辑章节",
        confirmText = "保存",
        onConfirm = { onSave(title, goal, participants, content) },
        confirmEnabled = title.isNotBlank(),
        dismissText = "取消",
        body = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CouiTextField(
                    title,
                    { title = it },
                    label = "章节标题",
                    singleLine = true,
                )
                CouiTextField(
                    goal,
                    { goal = it },
                    label = "本章目标",
                )
                CouiTextField(
                    participants,
                    { participants = it },
                    label = "出场人物（用顿号或逗号分隔）",
                )
                CouiTextField(
                    content,
                    { content = it },
                    label = "草稿内容",
                    minLines = 6,
                )
            }
        },
    )
}

@Composable
private fun ArchiveSessionDialog(
    sessions: List<top.wkbin.zaomeng.data.api.DialogueSessionDto>,
    onDismiss: () -> Unit,
    onArchive: (String, String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    CouiAlertDialog(
        onDismissRequest = onDismiss,
        title = "转为小说章节",
        confirmText = "取消",
        onConfirm = onDismiss,
        dismissText = null,
        body = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    CouiTextField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "章节标题（可选）",
                        singleLine = true,
                    )
                }
                item {
                    Text(
                        "至少需要 6 轮有效对话。",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
                if (sessions.isEmpty()) {
                    item {
                        EmptyState(
                            title = "还没有可归档的会话。",
                            sizeType = EmptyStateSizeType.Small,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                }
                items(sessions, key = { it.sessionId }) { session ->
                    Button(
                        onClick = { onArchive(session.sessionId, title) },
                        modifier = Modifier.fillMaxWidth(),
                        minWidth = 0.dp,
                    ) {
                        Text(
                            "转为小说 · " + session.lastEntryPreview.ifBlank { "会话 ${session.sessionId.takeLast(6)}" },
                            style = COUITheme.textStyles.buttonM,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
    )
}

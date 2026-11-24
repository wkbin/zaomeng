package top.wkbin.zaomeng.feature.rundetail

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.api.CardGreetingsDto
import top.wkbin.zaomeng.data.api.CARD_GREETING_CHAR_LIMIT
import top.wkbin.zaomeng.data.api.UpdateCardGreetingRequest
import top.wkbin.zaomeng.data.api.PersonaIndexDto
import top.wkbin.zaomeng.data.api.PersonaReviewDto
import top.wkbin.zaomeng.data.api.expandSillyTavernNames
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap
import top.wkbin.zaomeng.ui.roleplay.RoleplayMessageContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardPlayScreen(
    persona: PersonaIndexDto,
    avatar: ByteArray?,
    refreshEpoch: Int,
    viewModel: RunDetailViewModel,
    state: RunDetailUiState,
    onAvatarClick: () -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onWorldBooks: () -> Unit,
    onExport: () -> Unit,
    onDismissNotice: () -> Unit,
    onRetryExportDestination: () -> Unit,
    modifier: Modifier = Modifier,
    management: @Composable () -> Unit,
) {
    var selectedTab by rememberSaveable(viewModel.runId) { mutableIntStateOf(0) }
    var greetingRetry by remember { mutableIntStateOf(0) }
    val greetingResult by produceState<Result<CardGreetingsDto>?>(null, viewModel.runId, refreshEpoch, greetingRetry) {
        value = null
        value = loadCardContent(viewModel::loadCardGreetings)
    }
    var savedGreetings by remember(refreshEpoch, greetingRetry) { mutableStateOf<CardGreetingsDto?>(null) }
    val cardGreetings = (savedGreetings ?: greetingResult?.getOrNull())?.takeIf { it.character == persona.name }
    val greetings = cardGreetings?.options.orEmpty()
    val pager = rememberPagerState { greetings.size }
    val selectedGreeting = greetings.getOrNull(pager.settledPage)
    var savedIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(savedIndex, greetings) {
        val target = savedIndex ?: return@LaunchedEffect
        val page = greetings.indexOfFirst { it.index == target }
        if (page >= 0) pager.scrollToPage(page)
        savedIndex = null
    }
    val scope = rememberCoroutineScope()
    var fullGreeting by rememberSaveable { mutableStateOf(false) }
    var editingGreeting by rememberSaveable { mutableStateOf<Int?>(null) }
    var showManagement by rememberSaveable { mutableStateOf(false) }
    val bitmap = remember(avatar) { avatar?.let(::decodeImageBitmap) }
    var profileRetry by remember { mutableIntStateOf(0) }
    val profile by produceState<Result<PersonaReviewDto>?>(null, viewModel.runId, persona.name, refreshEpoch, profileRetry) {
        value = null
        value = loadCardContent { viewModel.loadCardProfile(persona.name) }
    }
    val metadata = profile?.getOrNull()?.cardMetadata

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            key(selectedTab) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            Surface(
                                onClick = onAvatarClick,
                                modifier = Modifier.size(width = 80.dp, height = 120.dp)
                                    .semantics { contentDescription = "查看或更换头像" },
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                if (bitmap != null) {
                                    Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                } else {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(persona.name.take(1), style = MaterialTheme.typography.displaySmall)
                                    }
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(persona.name, style = MaterialTheme.typography.headlineSmall)
                                if (metadata != null) {
                                    val byline = listOf(
                                        metadata.creator,
                                        metadata.characterVersion.takeIf(String::isNotBlank)?.let { "版本 $it" }.orEmpty(),
                                    ).filter(String::isNotBlank).joinToString(" · ")
                                    if (byline.isNotBlank()) {
                                        Text(byline, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (metadata.tags.isNotEmpty()) {
                                        Text(metadata.tags.take(3).joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            IconButton(onClick = onEdit) {
                                Icon(Icons.Outlined.Edit, "编辑角色卡")
                            }
                        }
                    }
                    PrimaryTabRow(selectedTabIndex = selectedTab) {
                        listOf("开场", "设定", "配置").forEachIndexed { index, title ->
                            Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.error.isNotBlank() || state.message.isNotBlank()) {
                            NoticeCard(
                                message = state.error.ifBlank { state.message },
                                error = state.error.isNotBlank(),
                                onDismiss = onDismissNotice,
                                actionLabel = if (state.error.isNotBlank() && state.exportedPackage != null) {
                                    "重新选择位置"
                                } else "",
                                onAction = onRetryExportDestination,
                            )
                        }
                        when (selectedTab) {
                            0 -> when {
                                greetingResult == null -> CircularProgressIndicator(Modifier.size(24.dp))
                                greetingResult?.isFailure == true -> {
                                    Text("开场加载失败。", color = MaterialTheme.colorScheme.error)
                                    TextButton(onClick = { greetingRetry++ }) { Text("重新加载开场") }
                                }
                                greetings.isEmpty() -> Text("这张卡没有预设开场，可以直接开始对话。")
                                else -> {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "开场 ${pager.currentPage + 1} / ${greetings.size}",
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        if (greetings.size > 1) {
                                            IconButton(
                                                onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                                                enabled = pager.currentPage > 0 && !pager.isScrollInProgress,
                                            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一个开场") }
                                            IconButton(
                                                onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                                                enabled = pager.currentPage < greetings.lastIndex && !pager.isScrollInProgress,
                                            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一个开场") }
                                        }
                                    }
                                    HorizontalPager(state = pager, key = { greetings[it].index }) { page ->
                                        Card(Modifier.fillMaxWidth()) {
                                            Box(Modifier.padding(16.dp).heightIn(max = 176.dp).clipToBounds()) {
                                                CardText(greetings[page].text, persona.name, maxLines = 6)
                                            }
                                        }
                                    }
                                }
                            }
                            1 -> CardSettings(persona.name, profile, onRetry = { profileRetry++ })
                            2 -> {
                                Card {
                                    CardConfigAction("世界书", "卡内条目与书卷挂载的世界书", onWorldBooks)
                                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                    CardConfigAction(
                                        "导出",
                                        "保存角色卡或书卷",
                                        onExport,
                                        enabled = !state.exporting,
                                    )
                                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                    CardConfigAction(
                                        if (showManagement) "收起书卷管理" else "书卷管理",
                                        "故事资料与高级操作",
                                        onClick = { showManagement = !showManagement },
                                    )
                                }
                                if (showManagement) management()
                            }
                        }
                        if (selectedTab == 0 && cardGreetings != null) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (selectedGreeting != null) {
                                    TextButton(onClick = { fullGreeting = true }) { Text("阅读完整开场") }
                                }
                                if (cardGreetings.revision.isNotBlank()) {
                                    if (selectedGreeting != null && !selectedGreeting.truncated) {
                                        TextButton(onClick = { editingGreeting = selectedGreeting.index }) {
                                            Text(if (selectedGreeting.index == 0) "编辑首句" else "编辑当前开场")
                                        }
                                    }
                                    if (greetings.none { it.index == 0 }) {
                                        TextButton(onClick = { editingGreeting = 0 }) { Text("添加首句") }
                                    }
                                    cardGreetings.nextAlternateIndex?.let { index ->
                                        TextButton(onClick = { editingGreeting = index }) { Text("添加备用开场") }
                                    }
                                }
                            }
                            if (selectedGreeting?.truncated == true) {
                                Text("当前开场超过 4000 字符，预览只显示开头。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (selectedTab == 0) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        if (state.cardChatError.isNotBlank()) {
                            Text(state.cardChatError, color = MaterialTheme.colorScheme.error)
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(onClick = onHistory, enabled = !state.startingCardChat) { Text("会话记录") }
                            Button(
                                onClick = { viewModel.startCardChat(persona.name, selectedGreeting?.index) },
                                enabled = greetingResult?.isSuccess == true && !pager.isScrollInProgress && !state.startingCardChat,
                                modifier = Modifier.weight(1f),
                            ) { Text(if (state.startingCardChat) "正在进入…" else "开始对话") }
                        }
                    }
                }
            }
        }
    }

    val editIndex = editingGreeting
    if (editIndex != null && cardGreetings != null && cardGreetings.revision.isNotBlank()) {
        GreetingEditor(
            index = editIndex,
            initialText = greetings.firstOrNull { it.index == editIndex }?.text.orEmpty(),
            revision = cardGreetings.revision,
            onCancel = { reload ->
                editingGreeting = null
                if (reload) greetingRetry++
            },
            save = viewModel::updateCardGreeting,
            onSaved = {
                savedGreetings = it
                savedIndex = editIndex
                editingGreeting = null
            },
        )
    }

    if (fullGreeting && selectedGreeting != null) {
        AlertDialog(
            onDismissRequest = { fullGreeting = false },
            title = { Text("开场 ${pager.settledPage + 1} / ${greetings.size}") },
            text = {
                Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    CardText(selectedGreeting.text, persona.name)
                }
            },
            confirmButton = { TextButton(onClick = { fullGreeting = false }) { Text("返回") } },
        )
    }
}

@Composable
private fun GreetingEditor(
    index: Int,
    initialText: String,
    revision: String,
    onCancel: (reload: Boolean) -> Unit,
    save: suspend (UpdateCardGreetingRequest) -> CardGreetingsDto,
    onSaved: (CardGreetingsDto) -> Unit,
) {
    var draft by rememberSaveable(index, revision) { mutableStateOf(initialText) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val tooLong = draft.length > CARD_GREETING_CHAR_LIMIT
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel(error.isNotBlank()) },
        title = { Text(if (index == 0) "编辑首句" else "备用开场 $index") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("开场正文") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                    enabled = !saving,
                    isError = tooLong,
                    supportingText = { Text("${draft.length} / $CARD_GREETING_CHAR_LIMIT 字符") },
                )
                if (initialText.isNotBlank()) {
                    Text("留空后保存会清空这条开场。", style = MaterialTheme.typography.bodySmall)
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && !tooLong && draft.trim() != initialText,
                onClick = {
                    saving = true
                    error = ""
                    scope.launch {
                        val result = loadCardContent { save(UpdateCardGreetingRequest(revision, index, draft)) }
                        saving = false
                        result.onSuccess(onSaved).onFailure { error = it.message ?: "开场保存失败。" }
                    }
                },
            ) { Text(if (saving) "正在保存…" else "保存开场") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = { onCancel(error.isNotBlank()) }) { Text("取消") } },
    )
}

@Composable
private fun CardConfigAction(title: String, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(description) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun CardSettings(character: String, result: Result<PersonaReviewDto>?, onRetry: () -> Unit) {
    when {
        result == null -> CircularProgressIndicator(Modifier.size(24.dp))
        result.isFailure -> {
            Text("设定加载失败。", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("重新加载设定") }
        }
        else -> {
            val profile = result.getOrThrow()
            val metadata = profile.cardMetadata
            if (metadata != null && metadata.creatorNotes.isNotBlank()) {
                Text("作者说明", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        metadata.creatorNotes,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (metadata.creatorNotesTruncated) {
                    Text("此处显示前 16000 字，完整说明可随角色卡导出。", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (metadata != null && metadata.tags.size > 3) {
                Text("标签", style = MaterialTheme.typography.titleMedium)
                Text(metadata.tags.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            }
            val fields = profile.cardFields.orEmpty()
            val sections = listOf(
                "description" to "角色描述",
                "personality" to "性格",
                "scenario" to "场景",
                "mes_example" to "对话示例",
            ).mapNotNull { (field, title) ->
                fields[field]?.takeIf(String::isNotBlank)?.let { title to it }
            }
            if (sections.isEmpty() && metadata?.creatorNotes.isNullOrBlank()) Text("这张卡没有填写角色设定。")
            for ((title, text) in sections) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) {
                    CardText(text, character, Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun CardText(text: String, character: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val expanded = remember(text, character) { expandSillyTavernNames(text, character, "你") }
    RoleplayMessageContent(
        text = expanded,
        style = MaterialTheme.typography.bodyLarge,
        baseColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
        maxProseLines = maxLines,
    )
}

private suspend fun <T> loadCardContent(load: suspend () -> T): Result<T> = try {
    Result.success(load())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}

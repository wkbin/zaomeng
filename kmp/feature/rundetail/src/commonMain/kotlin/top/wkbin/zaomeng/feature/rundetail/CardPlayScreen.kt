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
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.TabRow
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ChevronBackward
import io.github.suqi8.coui.kmp.icon.extended.ChevronForward
import io.github.suqi8.coui.kmp.icon.extended.Edit
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.api.CARD_GREETING_CHAR_LIMIT
import top.wkbin.zaomeng.data.api.CardGreetingDto
import top.wkbin.zaomeng.data.api.CardGreetingsDto
import top.wkbin.zaomeng.data.api.PersonaIndexDto
import top.wkbin.zaomeng.data.api.PersonaReviewDto
import top.wkbin.zaomeng.data.api.UpdateCardGreetingRequest
import top.wkbin.zaomeng.data.api.expandSillyTavernNames
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiPageContentScope
import top.wkbin.zaomeng.ui.coui.couiPageScroll
import top.wkbin.zaomeng.ui.graphics.decodeImageBitmap
import top.wkbin.zaomeng.ui.roleplay.RoleplayMessageContent

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
    /** 页面作用域，提供 COUI 滚动效果。 */
    pageScope: CouiPageContentScope,
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
    var fullGreeting by rememberSaveable { mutableStateOf(false) }
    var editingGreeting by rememberSaveable { mutableStateOf<Int?>(null) }
    var showManagement by rememberSaveable { mutableStateOf(false) }
    val bitmap = remember(avatar) { avatar?.let(::decodeImageBitmap) }
    var profileRetry by remember { mutableIntStateOf(0) }
    var metadataEditor by rememberSaveable(viewModel.runId, persona.name) { mutableStateOf<CardSettingsEditor?>(null) }
    val profile by produceState<Result<PersonaReviewDto>?>(null, viewModel.runId, persona.name, refreshEpoch, profileRetry) {
        value = null
        value = loadCardContent { viewModel.loadCardProfile(persona.name) }
    }
    val metadata = profile?.getOrNull()?.cardMetadata

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
        key(selectedTab) {
            Column(Modifier.weight(1f).couiPageScroll(pageScope)) {
                    Surface(color = COUITheme.colorScheme.backgroundWithCard) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            Surface(
                                onClick = onAvatarClick,
                                modifier = Modifier.size(width = 80.dp, height = 120.dp)
                                    .semantics { contentDescription = "查看或更换头像" },
                                shape = SquircleShape(COUIRoundCorner.Normal.M, COUIRoundCorner.Normal.Weight),
                                color = COUITheme.colorScheme.containerThemeHalftone,
                            ) {
                                if (bitmap != null) {
                                    Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                } else {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(persona.name.take(1), style = COUITheme.textStyles.displayM)
                                    }
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(persona.name, style = COUITheme.textStyles.headlineS)
                                if (metadata != null) {
                                    val byline = listOf(
                                        metadata.creator,
                                        metadata.characterVersion.takeIf(String::isNotBlank)?.let { "版本 $it" }.orEmpty(),
                                    ).filter(String::isNotBlank).joinToString(" · ")
                                    if (byline.isNotBlank()) {
                                        Text(byline, style = COUITheme.textStyles.bodyXS,
                                            color = COUITheme.colorScheme.labelSecondary,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (metadata.tags.isNotEmpty()) {
                                        Text(metadata.tags.take(3).joinToString(" · "), style = COUITheme.textStyles.buttonS,
                                            color = COUITheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            IconButton(onClick = onEdit) {
                                Icon(COUIIcons.Edit, "编辑角色卡")
                            }
                        }
                    }
                    TabRow(
                        tabs = listOf("开场", "设定", "配置"),
                        selectedTabIndex = selectedTab,
                        onTabSelected = { selectedTab = it },
                    )
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
                            0 -> CardOpeningTab(persona.name, greetingResult, greetings, pager, onRetry = { greetingRetry++ })
                            1 -> CardSettings(
                                persona.name, profile,
                                onRetry = { profileRetry++ },
                                onEditMetadata = { metadataEditor = CardSettingsEditor.Author },
                                onEditCharacterNote = { metadataEditor = CardSettingsEditor.CharacterNote },
                            )
                            2 -> {
                                Card {
                                    CardListGroup {
                                        item {
                                            CardConfigAction("世界书", "卡内条目与书卷挂载的世界书", onWorldBooks)
                                        }
                                        item {
                                            CardConfigAction(
                                                "导出",
                                                "保存角色卡或书卷",
                                                onExport,
                                                enabled = !state.exporting,
                                            )
                                        }
                                        item {
                                            CardConfigAction(
                                                if (showManagement) "收起书卷管理" else "书卷管理",
                                                "故事资料与高级操作",
                                                onClick = { showManagement = !showManagement },
                                            )
                                        }
                                    }
                                }
                                if (showManagement) management()
                            }
                        }
                        if (selectedTab == 0) {
                            CardOpeningActions(
                                cardGreetings, selectedGreeting,
                                onRead = { fullGreeting = true },
                                onEdit = { editingGreeting = it },
                            )
                        }
                    }
                }
            }
            if (selectedTab == 0) {
                Surface(color = COUITheme.colorScheme.backgroundWithCard) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        if (state.cardChatError.isNotBlank()) {
                            Text(state.cardChatError, color = COUITheme.colorScheme.error)
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = onHistory,
                                enabled = !state.startingCardChat,
                                colors = ButtonDefaults.buttonColors(),
                            ) { Text("会话记录") }
                            Button(
                                onClick = { viewModel.startCardChat(persona.name, selectedGreeting?.index) },
                                enabled = greetingResult?.isSuccess == true && !pager.isScrollInProgress && !state.startingCardChat,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColorsPrimary(),
                            ) { Text(if (state.startingCardChat) "正在进入…" else "开始对话") }
                        }
                    }
                }
            }
        }
    }

    if (metadataEditor != null && metadata != null && metadata.revision.isNotBlank()) {
        val closeEditor: (Boolean) -> Unit = { reload ->
            metadataEditor = null
            if (reload) {
                profileRetry++
                greetingRetry++
            }
        }
        when (metadataEditor) {
            CardSettingsEditor.Author -> CardMetadataEditor(metadata, { viewModel.updateCardMetadata(persona.name, it) }, closeEditor)
            CardSettingsEditor.CharacterNote -> CardCharacterNoteEditor(metadata, { viewModel.updateCardMetadata(persona.name, it) }, closeEditor)
            null -> Unit
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
        CouiAlertDialog(
            onDismissRequest = { fullGreeting = false },
            title = "开场 ${pager.settledPage + 1} / ${greetings.size}",
            confirmText = "返回",
            onConfirm = { fullGreeting = false },
            dismissText = null,
            body = {
                Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    CardText(selectedGreeting.text, persona.name)
                }
            },
        )
    }
}

@Composable
private fun CardOpeningTab(
    character: String,
    result: Result<CardGreetingsDto>?,
    greetings: List<CardGreetingDto>,
    pager: PagerState,
    onRetry: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    when {
        result == null -> CircularProgressIndicator(modifier = Modifier.size(24.dp), size = 24.dp)
        result?.isFailure == true -> {
            Text("开场加载失败。", color = COUITheme.colorScheme.error)
            TextButton(
                text = "重新加载开场",
                onClick = onRetry,
                minWidth = 0.dp,
            )
        }
        greetings.isEmpty() -> Text("这张卡没有预设开场，可以直接开始对话。")
        else -> {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "开场 ${pager.currentPage + 1} / ${greetings.size}",
                    modifier = Modifier.weight(1f),
                    style = COUITheme.textStyles.headlineXS,
                )
                if (greetings.size > 1) {
                    IconButton(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                        enabled = pager.currentPage > 0 && !pager.isScrollInProgress,
                    ) { Icon(COUIIcons.ChevronBackward, "上一个开场") }
                    IconButton(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        enabled = pager.currentPage < greetings.lastIndex && !pager.isScrollInProgress,
                    ) { Icon(COUIIcons.ChevronForward, "下一个开场") }
                }
            }
            HorizontalPager(state = pager, key = { greetings[it].index }) { page ->
                Card(Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(16.dp).heightIn(max = 176.dp).clipToBounds()) {
                        CardText(greetings[page].text, character, maxLines = 6)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardOpeningActions(
    cardGreetings: CardGreetingsDto?,
    selectedGreeting: CardGreetingDto?,
    onRead: () -> Unit,
    onEdit: (Int) -> Unit,
) {
    if (cardGreetings != null) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selectedGreeting != null) {
                TextButton(text = "阅读完整开场", onClick = onRead, minWidth = 0.dp)
            }
            if (cardGreetings.revision.isNotBlank()) {
                if (selectedGreeting != null && !selectedGreeting.truncated) {
                    TextButton(
                        text = if (selectedGreeting.index == 0) "编辑首句" else "编辑当前开场",
                        onClick = { onEdit(selectedGreeting.index) },
                        minWidth = 0.dp,
                    )
                }
                if (cardGreetings.options.none { it.index == 0 }) {
                    TextButton(text = "添加首句", onClick = { onEdit(0) }, minWidth = 0.dp)
                }
                cardGreetings.nextAlternateIndex?.let { index ->
                    TextButton(
                        text = "添加备用开场",
                        onClick = { onEdit(index) },
                        minWidth = 0.dp,
                    )
                }
            }
        }
        if (selectedGreeting?.truncated == true) {
            Text("当前开场超过 4000 字符，预览只显示开头。", style = COUITheme.textStyles.bodyXS)
        }
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
    var fieldValue by rememberSaveable(index, revision, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialText, selection = TextRange(initialText.length)))
    }
    val draft = fieldValue.text
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val tooLong = draft.length > CARD_GREETING_CHAR_LIMIT
    CouiAlertDialog(
        onDismissRequest = { if (!saving) onCancel(error.isNotBlank()) },
        title = if (index == 0) "编辑首句" else "备用开场 $index",
        confirmText = if (saving) "正在保存…" else "保存开场",
        onConfirm = {
            saving = true
            error = ""
            scope.launch {
                val result = loadCardContent { save(UpdateCardGreetingRequest(revision, index, draft)) }
                saving = false
                result.onSuccess(onSaved).onFailure { error = it.message ?: "开场保存失败。" }
            }
        },
        confirmEnabled = !saving && !tooLong && draft.trim() != initialText,
        dismissText = "取消",
        onDismiss = { if (!saving) onCancel(error.isNotBlank()) },
        body = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = fieldValue,
                    onValueChange = { fieldValue = it },
                    label = "开场正文",
                    modifier = Modifier.fillMaxWidth(),
                    backgroundMode = TextFieldMode.Line,
                    singleLine = false,
                    minLines = 4,
                    maxLines = 8,
                    enabled = !saving,
                    isError = tooLong,
                )
                Text(
                    "${draft.length} / $CARD_GREETING_CHAR_LIMIT 字符",
                    style = COUITheme.textStyles.bodyXS,
                    color = if (tooLong) COUITheme.colorScheme.error else COUITheme.colorScheme.labelSecondary,
                )
                if (initialText.isNotBlank()) {
                    Text("留空后保存会清空这条开场。", style = COUITheme.textStyles.bodyXS)
                }
                if (error.isNotBlank()) Text(error, color = COUITheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun CardConfigAction(title: String, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    ArrowPreference(
        title = title,
        summary = description,
        enabled = enabled,
        onClick = onClick,
    )
}

private enum class CardSettingsEditor { Author, CharacterNote }

@Composable
private fun CardSettings(
    character: String,
    result: Result<PersonaReviewDto>?,
    onRetry: () -> Unit,
    onEditMetadata: () -> Unit,
    onEditCharacterNote: () -> Unit,
) {
    when {
        result == null -> CircularProgressIndicator(modifier = Modifier.size(24.dp), size = 24.dp)
        result.isFailure -> {
            Text("设定加载失败。", color = COUITheme.colorScheme.error)
            TextButton(text = "重新加载设定", onClick = onRetry, minWidth = 0.dp)
        }
        else -> {
            val profile = result.getOrThrow()
            val metadata = profile.cardMetadata
            if (metadata != null && metadata.revision.isNotBlank()) {
                TextButton(text = "编辑作者资料", onClick = onEditMetadata, minWidth = 0.dp)
                val note = metadata.characterNote
                val noteSummary = when {
                    metadata.characterNoteTruncated -> "原卡注记超过编辑范围，导出会保留全文"
                    note.text.isBlank() -> "未设置"
                    else -> "深度 ${note.depth} · ${note.role.characterNoteLabel}"
                }
                Card(Modifier.fillMaxWidth()) {
                    if (metadata.characterNoteTruncated) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("角色注记", style = COUITheme.textStyles.bodyM)
                            Text(noteSummary, style = COUITheme.textStyles.bodyXS)
                        }
                    } else {
                        CardListGroup {
                            item { CardConfigAction("角色注记", noteSummary, onEditCharacterNote) }
                        }
                    }
                }
            }
            if (metadata != null && metadata.creatorNotes.isNotBlank()) {
                Text("作者说明", style = COUITheme.textStyles.headlineXS)
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        metadata.creatorNotes,
                        modifier = Modifier.padding(16.dp),
                        style = COUITheme.textStyles.bodyXL,
                    )
                }
                if (metadata.creatorNotesTruncated) {
                    Text("此处显示前 16000 字，完整说明可随角色卡导出。", style = COUITheme.textStyles.bodyXS)
                }
            }
            if (metadata != null && metadata.tags.size > 3) {
                Text("标签", style = COUITheme.textStyles.headlineXS)
                Text(metadata.tags.joinToString(" · "), style = COUITheme.textStyles.bodyM)
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
            if (sections.isEmpty() && metadata?.creatorNotes.isNullOrBlank() && metadata?.characterNote?.text.isNullOrBlank()) {
                Text("这张卡没有填写角色设定。")
            }
            for ((title, text) in sections) {
                Text(title, style = COUITheme.textStyles.headlineXS)
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
        style = COUITheme.textStyles.bodyXL,
        baseColor = COUITheme.colorScheme.labelPrimary,
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

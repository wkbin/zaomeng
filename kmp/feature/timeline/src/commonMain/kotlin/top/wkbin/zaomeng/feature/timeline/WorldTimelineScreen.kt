package top.wkbin.zaomeng.feature.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.EmptyStateSizeType
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.TabRow
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.Edit
import io.github.suqi8.coui.kmp.icon.extended.Lock
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.icon.extended.Report
import io.github.suqi8.coui.kmp.icon.extended.Unlock
import io.github.suqi8.coui.kmp.overlay.OverlayListPopup
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.data.api.SaveWorldFactRequest
import top.wkbin.zaomeng.data.api.WorldFactDto
import top.wkbin.zaomeng.data.api.WorldTimelineItemDto
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiPageScaffold
import top.wkbin.zaomeng.ui.coui.CouiTextField
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.couiPageScroll
import top.wkbin.zaomeng.ui.coui.isWideWindow
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay

private val factCategories = listOf(
    "event" to "事件",
    "location" to "位置",
    "possession" to "持有物",
    "status" to "状态",
    "commitment" to "承诺",
    "secret" to "秘密",
    "relationship" to "关系",
    "setting" to "设定",
)

@Composable
fun WorldTimelineScreen(
    viewModel: WorldTimelineViewModel,
    onBack: () -> Unit,
    onOpenChat: (runId: String, sessionId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var editingFact by remember { mutableStateOf<WorldFactDto?>(null) }
    var creatingFact by rememberSaveable { mutableStateOf(false) }
    var deletingFact by remember { mutableStateOf<WorldFactDto?>(null) }

    CouiPageScaffold(
        title = "故事时间线",
        topBarStyle = CouiTopBarStyle.Small,
        onBack = onBack,
        wide = isWideWindow(),
        actions = {
            if (selectedTab == 1) {
                IconButton(onClick = { creatingFact = true }) {
                    Icon(COUIIcons.Add, contentDescription = "添加事实")
                }
            }
            IconButton(onClick = viewModel::load, enabled = !state.refreshing) {
                if (state.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), size = 22.dp, strokeWidth = 2.dp)
                } else {
                    Icon(COUIIcons.Refresh, contentDescription = "刷新")
                }
            }
        },
        topBarBottomContent = {
            TabRow(
                tabs = listOf("时间线", "剧情事实"),
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
            )
        },
    ) { scope ->
        if (creatingFact || editingFact != null) {
            WorldFactEditorDialog(
                fact = editingFact,
                saving = state.savingFactId.isNotBlank(),
                onDismiss = {
                    creatingFact = false
                    editingFact = null
                },
                onSave = { request ->
                    viewModel.save(editingFact, request)
                    creatingFact = false
                    editingFact = null
                },
            )
        }

        deletingFact?.let { fact ->
            CouiAlertDialog(
                onDismissRequest = { if (state.deletingFactId.isBlank()) deletingFact = null },
                title = "删除这条事实？",
                confirmText = "删除",
                onConfirm = {
                    deletingFact = null
                    viewModel.delete(fact)
                },
                confirmEnabled = state.deletingFactId.isBlank(),
                dismissText = "取消",
                onDismiss = { deletingFact = null },
                body = { Text(fact.summary) },
            )
        }

        when {
            state.loading -> Box(
                Modifier.fillMaxSize().padding(scope.contentPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            state.memory == null -> EmptyStatePage(
                text = state.error.ifBlank { "故事记忆读取失败。" },
                onRetry = viewModel::load,
                modifier = Modifier.padding(scope.contentPadding),
            )
            else -> {
                val memory = requireNotNull(state.memory)
                val contentModifier = Modifier.padding(scope.contentPadding).couiPageScroll(scope)
                if (selectedTab == 0) {
                    TimelineContent(
                        items = memory.timeline,
                        notice = state.error.ifBlank { state.message },
                        noticeIsError = state.error.isNotBlank(),
                        onDismissNotice = viewModel::dismissNotice,
                        onOpenChat = { sessionId -> onOpenChat(viewModel.runId, sessionId) },
                        modifier = contentModifier,
                    )
                } else {
                    FactsContent(
                        facts = memory.facts,
                        notice = state.error.ifBlank { state.message },
                        noticeIsError = state.error.isNotBlank(),
                        savingFactId = state.savingFactId,
                        deletingFactId = state.deletingFactId,
                        onDismissNotice = viewModel::dismissNotice,
                        onEdit = { editingFact = it },
                        onToggleLock = viewModel::toggleLocked,
                        onDelete = { deletingFact = it },
                        onOpenChat = { sessionId -> onOpenChat(viewModel.runId, sessionId) },
                        modifier = contentModifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineContent(
    items: List<WorldTimelineItemDto>,
    notice: String,
    noticeIsError: Boolean,
    onDismissNotice: () -> Unit,
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCharacter by rememberSaveable { mutableStateOf("") }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    val characters = remember(items) { items.flatMap { it.participants }.distinct().sorted() }
    val visibleItems = remember(items, selectedCharacter, newestFirst) {
        items.filter { selectedCharacter.isBlank() || selectedCharacter in it.participants }
            .let { if (newestFirst) it.asReversed() else it }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FilterBar(
                characters = characters,
                selectedCharacter = selectedCharacter,
                onCharacterChange = { selectedCharacter = it },
                newestFirst = newestFirst,
                onOrderChange = { newestFirst = !newestFirst },
            )
        }
        if (notice.isNotBlank()) item { Notice(notice, noticeIsError, onDismissNotice) }
        if (visibleItems.isEmpty()) {
            item { EmptyMessage("完成对话后，重要剧情变化会自动出现在这里。") }
        } else {
            items(visibleItems, key = { it.timelineId.ifBlank { "${it.sourceSessionId}:${it.sourceTurnId}" } }) { item ->
                TimelineCard(item, onOpenChat)
            }
        }
    }
}

@Composable
private fun FactsContent(
    facts: List<WorldFactDto>,
    notice: String,
    noticeIsError: Boolean,
    savingFactId: String,
    deletingFactId: String,
    onDismissNotice: () -> Unit,
    onEdit: (WorldFactDto) -> Unit,
    onToggleLock: (WorldFactDto) -> Unit,
    onDelete: (WorldFactDto) -> Unit,
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCategory by rememberSaveable { mutableStateOf("") }
    var selectedCharacter by rememberSaveable { mutableStateOf("") }
    val characters = remember(facts) { facts.flatMap { it.characters }.distinct().sorted() }
    val visibleFactGroups = remember(facts, selectedCategory, selectedCharacter) {
        facts.asReversed().filter {
            (selectedCategory.isBlank() || it.category == selectedCategory) &&
                (selectedCharacter.isBlank() || selectedCharacter in it.characters)
        }.groupForDisplay()
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(
                        selected = selectedCategory.isBlank(),
                        onClick = { selectedCategory = "" },
                        label = "全部",
                    )
                    factCategories.forEach { (value, label) ->
                        Chip(
                            selected = selectedCategory == value,
                            onClick = { selectedCategory = value },
                            label = label,
                        )
                    }
                }
                CharacterFilter(
                    characters = characters,
                    selected = selectedCharacter,
                    onSelected = { selectedCharacter = it },
                )
            }
        }
        if (notice.isNotBlank()) item { Notice(notice, noticeIsError, onDismissNotice) }
        if (visibleFactGroups.isEmpty()) {
            item { EmptyMessage("暂无符合筛选条件的剧情事实。你也可以手动添加并锁定重要设定。") }
        } else {
            items(visibleFactGroups, key = FactDisplayGroup::key) { group ->
                if (group.facts.size == 1) {
                    val fact = group.facts.single()
                    FactCard(
                        fact = fact,
                        busy = savingFactId == fact.factId || deletingFactId == fact.factId,
                        onEdit = { onEdit(fact) },
                        onToggleLock = { onToggleLock(fact) },
                        onDelete = { onDelete(fact) },
                        onOpenChat = onOpenChat,
                    )
                } else {
                    FactTurnGroupCard(
                        group = group,
                        savingFactId = savingFactId,
                        deletingFactId = deletingFactId,
                        onEdit = onEdit,
                        onToggleLock = onToggleLock,
                        onDelete = onDelete,
                        onOpenChat = onOpenChat,
                    )
                }
            }
        }
    }
}

private data class FactDisplayGroup(
    val key: String,
    val facts: List<WorldFactDto>,
)

private fun List<WorldFactDto>.groupForDisplay(): List<FactDisplayGroup> {
    val groups = linkedMapOf<String, MutableList<WorldFactDto>>()
    forEach { fact ->
        val sourceSessionId = fact.sourceSessionId.trim()
        val sourceTurnId = fact.sourceTurnId.trim()
        val key = if (
            fact.source == "dialogue" && sourceSessionId.isNotBlank() && sourceTurnId.isNotBlank()
        ) {
            "turn:$sourceSessionId:$sourceTurnId"
        } else {
            "fact:${fact.factId}"
        }
        groups.getOrPut(key) { mutableListOf() } += fact
    }
    return groups.map { (key, groupedFacts) -> FactDisplayGroup(key, groupedFacts) }
}

@Composable
private fun FilterBar(
    characters: List<String>,
    selectedCharacter: String,
    onCharacterChange: (String) -> Unit,
    newestFirst: Boolean,
    onOrderChange: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterFilter(
            characters,
            selectedCharacter,
            onCharacterChange,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            text = if (newestFirst) "最新在前" else "最早在前",
            onClick = onOrderChange,
            minWidth = 0.dp,
        )
    }
}

@Composable
private fun CharacterFilter(
    characters: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Button(
            onClick = { expanded = true },
            colors = ButtonDefaults.buttonColors(),
            minWidth = 0.dp,
        ) {
            Text(
                if (selected.isBlank()) "全部人物" else selected,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(COUIIcons.More, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        OverlayListPopup(show = expanded, onDismissRequest = { expanded = false }) {
            ListPopupColumn {
                val optionSize = characters.size + 1
                DropdownImpl(
                    text = "全部人物",
                    optionSize = optionSize,
                    isSelected = selected.isBlank(),
                    index = 0,
                    onSelectedIndexChange = {
                        expanded = false
                        onSelected("")
                    },
                )
                characters.forEachIndexed { offset, name ->
                    DropdownImpl(
                        text = name,
                        optionSize = optionSize,
                        isSelected = name == selected,
                        index = offset + 1,
                        onSelectedIndexChange = {
                            expanded = false
                            onSelected(name)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineCard(item: WorldTimelineItemDto, onOpenChat: (String) -> Unit) {
    val warning = item.consistencyStatus !in listOf("", "pass")
    Card(
        colors = CardDefaults.defaultColors(
            color = if (warning) COUITheme.colorScheme.errorTextBg
            else COUITheme.colorScheme.backgroundWithCard,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (warning) {
                    Icon(COUIIcons.Report, contentDescription = null, tint = COUITheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    item.title.ifBlank { "剧情推进" },
                    modifier = Modifier.weight(1f),
                    style = COUITheme.textStyles.headlineXS,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MetadataLine(item.timeHint, item.location, item.participants.joinToString("、"))
            if (warning) {
                Text("这一轮存在一致性警告，建议进入来源会话检查。", style = COUITheme.textStyles.bodyXS)
            }
            SourceAction(item.sourceSessionId, item.updatedAt, onOpenChat)
        }
    }
}

@Composable
private fun FactCard(
    fact: WorldFactDto,
    busy: Boolean,
    onEdit: () -> Unit,
    onToggleLock: () -> Unit,
    onDelete: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.defaultColors(
            color = if (fact.locked) COUITheme.colorScheme.containerThemeHalftone
            else COUITheme.colorScheme.backgroundWithCard,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        categoryLabel(fact.category) + if (!fact.active) " · 已停用" else "",
                        style = COUITheme.textStyles.buttonS,
                        color = COUITheme.colorScheme.primary,
                    )
                    Text(fact.summary, style = COUITheme.textStyles.bodyXL)
                }
            }
            MetadataLine(fact.timeHint, fact.location, fact.characters.joinToString("、"))
            if (fact.locked) {
                Text(
                    "已锁定：后续对话会优先遵守这条事实。",
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelPrimary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), size = 22.dp, strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onToggleLock) {
                        Icon(
                            if (fact.locked) COUIIcons.Lock else COUIIcons.Unlock,
                            contentDescription = if (fact.locked) "取消锁定" else "锁定事实",
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(COUIIcons.Edit, contentDescription = "编辑")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(COUIIcons.Delete, contentDescription = "删除")
                    }
                }
            }
            SourceAction(fact.sourceSessionId, fact.updatedAt, onOpenChat)
        }
    }
}

@Composable
private fun FactTurnGroupCard(
    group: FactDisplayGroup,
    savingFactId: String,
    deletingFactId: String,
    onEdit: (WorldFactDto) -> Unit,
    onToggleLock: (WorldFactDto) -> Unit,
    onDelete: (WorldFactDto) -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val facts = group.facts
    val sourceSessionId = facts.first().sourceSessionId
    val updatedAt = facts.maxByOrNull(WorldFactDto::updatedAt)?.updatedAt.orEmpty()
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "本轮剧情变化",
                style = COUITheme.textStyles.bodyM,
                fontWeight = FontWeight.SemiBold,
            )
            facts.forEachIndexed { index, fact ->
                if (index > 0) HorizontalDivider()
                FactGroupEntry(
                    fact = fact,
                    busy = savingFactId == fact.factId || deletingFactId == fact.factId,
                    onEdit = { onEdit(fact) },
                    onToggleLock = { onToggleLock(fact) },
                    onDelete = { onDelete(fact) },
                )
            }
            SourceAction(sourceSessionId, updatedAt, onOpenChat)
        }
    }
}

@Composable
private fun FactGroupEntry(
    fact: WorldFactDto,
    busy: Boolean,
    onEdit: () -> Unit,
    onToggleLock: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            categoryLabel(fact.category) + if (!fact.active) " · 已停用" else "",
            style = COUITheme.textStyles.buttonS,
            color = COUITheme.colorScheme.primary,
        )
        Text(fact.summary, style = COUITheme.textStyles.bodyM)
        MetadataLine(fact.timeHint, fact.location, fact.characters.joinToString("、"))
        if (fact.locked) {
            Text(
                "已锁定",
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.primary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), size = 22.dp, strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onToggleLock) {
                    Icon(
                        if (fact.locked) COUIIcons.Lock else COUIIcons.Unlock,
                        contentDescription = if (fact.locked) "取消锁定" else "锁定事实",
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(COUIIcons.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(COUIIcons.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

@Composable
private fun MetadataLine(vararg values: String) {
    val text = values.map(String::trim).filter(String::isNotBlank).joinToString(" · ")
    if (text.isNotBlank()) {
        Text(text, style = COUITheme.textStyles.bodyXS, color = COUITheme.colorScheme.labelSecondary)
    }
}

@Composable
private fun SourceAction(sessionId: String, updatedAt: String, onOpenChat: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            updatedAt.toLocalDateTimeDisplay().ifBlank { "时间未知" },
            modifier = Modifier.weight(1f),
            style = COUITheme.textStyles.bodyXS,
            color = COUITheme.colorScheme.labelSecondary,
        )
        if (sessionId.isNotBlank()) {
            TextButton(text = "查看来源", onClick = { onOpenChat(sessionId) }, minWidth = 0.dp)
        } else {
            Text("人工记录", style = COUITheme.textStyles.bodyXS)
        }
    }
}

@Composable
private fun WorldFactEditorDialog(
    fact: WorldFactDto?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (SaveWorldFactRequest) -> Unit,
) {
    var summary by remember(fact) { mutableStateOf(fact?.summary.orEmpty()) }
    var category by remember(fact) { mutableStateOf(fact?.category ?: "event") }
    var characters by remember(fact) { mutableStateOf(fact?.characters?.joinToString("、").orEmpty()) }
    var location by remember(fact) { mutableStateOf(fact?.location.orEmpty()) }
    var timeHint by remember(fact) { mutableStateOf(fact?.timeHint.orEmpty()) }
    var locked by remember(fact) { mutableStateOf(fact?.locked ?: false) }
    var active by remember(fact) { mutableStateOf(fact?.active ?: true) }
    var categoryExpanded by remember { mutableStateOf(false) }
    CouiAlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = if (fact == null) "添加剧情事实" else "编辑剧情事实",
        confirmText = if (saving) "保存中" else "保存",
        onConfirm = {
            onSave(
                SaveWorldFactRequest(
                    category = category,
                    summary = summary.trim(),
                    characters = characters.split(Regex("[,，、]"))
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .distinct(),
                    location = location.trim(),
                    timeHint = timeHint.trim(),
                    locked = locked,
                    active = active,
                )
            )
        },
        confirmEnabled = summary.isNotBlank() && !saving,
        dismissText = "取消",
        onDismiss = { if (!saving) onDismiss() },
        body = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box {
                    TextButton(
                        text = "类别：${categoryLabel(category)}",
                        onClick = { categoryExpanded = true },
                        minWidth = 0.dp,
                    )
                    OverlayListPopup(show = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        ListPopupColumn {
                            factCategories.forEachIndexed { index, (value, label) ->
                                DropdownImpl(
                                    text = label,
                                    optionSize = factCategories.size,
                                    isSelected = value == category,
                                    index = index,
                                    onSelectedIndexChange = {
                                        categoryExpanded = false
                                        category = value
                                    },
                                )
                            }
                        }
                    }
                }
                CouiTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = "事实内容",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 5,
                )
                CouiTextField(
                    value = characters,
                    onValueChange = { characters = it },
                    label = "相关人物（用顿号分隔）",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CouiTextField(
                        value = timeHint,
                        onValueChange = { timeHint = it },
                        label = "时间",
                        modifier = Modifier.weight(1f),
                    )
                    CouiTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = "地点",
                        modifier = Modifier.weight(1f),
                    )
                }
                HorizontalDivider()
                SettingSwitch("锁定事实", locked) { locked = it }
                SettingSwitch("当前有效", active) { active = it }
            }
        },
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchPreference(
        title = label,
        checked = checked,
        onCheckedChange = onCheckedChange,
        insideMargin = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
    )
}

@Composable
private fun Notice(text: String, error: Boolean, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.defaultColors(
            color = if (error) COUITheme.colorScheme.errorTextBg
            else COUITheme.colorScheme.containerThemeHalftone,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = COUITheme.textStyles.bodyXS)
            TextButton(text = "关闭", onClick = onDismiss, minWidth = 0.dp)
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    EmptyState(
        title = text,
        sizeType = EmptyStateSizeType.Small,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
    )
}

@Composable
private fun EmptyStatePage(text: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        title = text,
        actionText = "重试",
        onActionClick = onRetry,
        sizeType = EmptyStateSizeType.Small,
        modifier = modifier.fillMaxSize(),
    )
}

private fun categoryLabel(category: String): String =
    factCategories.firstOrNull { it.first == category }?.second ?: "事件"

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import top.wkbin.zaomeng.data.api.SaveWorldFactRequest
import top.wkbin.zaomeng.data.api.WorldFactDto
import top.wkbin.zaomeng.data.api.WorldTimelineItemDto
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

@OptIn(ExperimentalMaterial3Api::class)
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
        AlertDialog(
            onDismissRequest = { if (state.deletingFactId.isBlank()) deletingFact = null },
            title = { Text("删除这条事实？") },
            text = { Text(fact.summary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingFact = null
                        viewModel.delete(fact)
                    },
                    enabled = state.deletingFactId.isBlank(),
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingFact = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("故事时间线") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (selectedTab == 1) {
                            IconButton(onClick = { creatingFact = true }) {
                                Icon(Icons.Default.Add, contentDescription = "添加事实")
                            }
                        }
                        IconButton(onClick = viewModel::load, enabled = !state.refreshing) {
                            if (state.refreshing) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新")
                            }
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("时间线") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("剧情事实") },
                    )
                }
            }
        },
    ) { innerPadding ->
        when {
            state.loading -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            state.memory == null -> EmptyState(
                text = state.error.ifBlank { "故事记忆读取失败。" },
                onRetry = viewModel::load,
                modifier = Modifier.padding(innerPadding),
            )
            else -> {
                val memory = requireNotNull(state.memory)
                if (selectedTab == 0) {
                    TimelineContent(
                        items = memory.timeline,
                        notice = state.error.ifBlank { state.message },
                        noticeIsError = state.error.isNotBlank(),
                        onDismissNotice = viewModel::dismissNotice,
                        onOpenChat = { sessionId -> onOpenChat(viewModel.runId, sessionId) },
                        modifier = Modifier.padding(innerPadding),
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
                        modifier = Modifier.padding(innerPadding),
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
                    FilterChip(
                        selected = selectedCategory.isBlank(),
                        onClick = { selectedCategory = "" },
                        label = { Text("全部") },
                    )
                    factCategories.forEach { (value, label) ->
                        FilterChip(
                            selected = selectedCategory == value,
                            onClick = { selectedCategory = value },
                            label = { Text(label) },
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
        TextButton(onClick = onOrderChange) { Text(if (newestFirst) "最新在前" else "最早在前") }
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
        TextButton(onClick = { expanded = true }) {
            Text(
                if (selected.isBlank()) "全部人物" else selected,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.MoreVert, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("全部人物") },
                onClick = { onSelected(""); expanded = false },
            )
            characters.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelected(name); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun TimelineCard(item: WorldTimelineItemDto, onOpenChat: (String) -> Unit) {
    val warning = item.consistencyStatus !in listOf("", "pass")
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (warning) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (warning) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    item.title.ifBlank { "剧情推进" },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MetadataLine(item.timeHint, item.location, item.participants.joinToString("、"))
            if (warning) {
                Text("这一轮存在一致性警告，建议进入来源会话检查。", style = MaterialTheme.typography.bodySmall)
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
        colors = CardDefaults.cardColors(
            containerColor = if (fact.locked) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        categoryLabel(fact.category) + if (!fact.active) " · 已停用" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(fact.summary, style = MaterialTheme.typography.bodyLarge)
                }
            }
            MetadataLine(fact.timeHint, fact.location, fact.characters.joinToString("、"))
            if (fact.locked) {
                Text(
                    "已锁定：后续对话会优先遵守这条事实。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onToggleLock) {
                        Icon(
                            if (fact.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (fact.locked) "取消锁定" else "锁定事实",
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "本轮剧情变化",
                style = MaterialTheme.typography.titleSmall,
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
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(fact.summary, style = MaterialTheme.typography.bodyMedium)
        MetadataLine(fact.timeHint, fact.location, fact.characters.joinToString("、"))
        if (fact.locked) {
            Text(
                "已锁定",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onToggleLock) {
                    Icon(
                        if (fact.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = if (fact.locked) "取消锁定" else "锁定事实",
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

@Composable
private fun MetadataLine(vararg values: String) {
    val text = values.map(String::trim).filter(String::isNotBlank).joinToString(" · ")
    if (text.isNotBlank()) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceAction(sessionId: String, updatedAt: String, onOpenChat: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            updatedAt.toLocalDateTimeDisplay().ifBlank { "时间未知" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sessionId.isNotBlank()) {
            TextButton(onClick = { onOpenChat(sessionId) }) { Text("查看来源") }
        } else {
            Text("人工记录", style = MaterialTheme.typography.labelSmall)
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
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (fact == null) "添加剧情事实" else "编辑剧情事实") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box {
                    TextButton(onClick = { categoryExpanded = true }) {
                        Text("类别：${categoryLabel(category)}")
                    }
                    DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        factCategories.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { category = value; categoryExpanded = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("事实内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                )
                OutlinedTextField(
                    value = characters,
                    onValueChange = { characters = it },
                    label = { Text("相关人物（用顿号分隔）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = timeHint,
                        onValueChange = { timeHint = it },
                        label = { Text("时间") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text("地点") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                HorizontalDivider()
                SettingSwitch("锁定事实", locked) { locked = it }
                SettingSwitch("当前有效", active) { active = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
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
                enabled = summary.isNotBlank() && !saving,
            ) { Text(if (saving) "保存中" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun Notice(text: String, error: Boolean, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmptyState(text: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

private fun categoryLabel(category: String): String =
    factCategories.firstOrNull { it.first == category }?.second ?: "事件"

package top.wkbin.zaomeng.feature.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.data.ReusableCardKind
import top.wkbin.zaomeng.data.api.ReusableCardDto
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val cardKinds = listOf(
    ReusableCardKind.Scene,
    ReusableCardKind.Self,
    ReusableCardKind.Opening,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardLibraryScreen(
    viewModel: CardLibraryViewModel,
    showBackButton: Boolean = true,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ReusableCardDto?>(null) }

    pendingDelete?.let { card ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除${state.kind.displayName}？") },
            text = { Text("已经使用它创建的会话不会被删除，但以后不能再选择这张卡。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(card.cardId)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    if (state.editorVisible) {
        CardEditorDialog(
            state = state,
            onFieldChange = viewModel::updateField,
            onSave = viewModel::save,
            onDismiss = viewModel::closeEditor,
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("创作资料库") },
                    navigationIcon = {
                        if (showBackButton) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                    actions = {
                        if (state.kind != ReusableCardKind.Opening) {
                            IconButton(onClick = viewModel::generate, enabled = !state.generating) {
                                if (state.generating) {
                                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Outlined.AutoAwesome, contentDescription = "自动生成")
                                }
                            }
                        }
                        IconButton(onClick = viewModel::load, enabled = !state.loading && !state.refreshing) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = cardKinds.indexOf(state.kind).coerceAtLeast(0)) {
                    cardKinds.forEach { kind ->
                        Tab(
                            selected = state.kind == kind,
                            onClick = { viewModel.selectKind(kind) },
                            text = { Text(kind.displayName) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::createCard) {
                Icon(Icons.Default.Add, contentDescription = "新建${state.kind.displayName}")
            }
        },
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 380.dp),
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.error.isNotBlank() || state.message.isNotBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (state.error.isNotBlank()) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        ),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(state.error.ifBlank { state.message }, Modifier.weight(1f))
                            TextButton(onClick = viewModel::dismissNotice) { Text("知道了") }
                        }
                    }
                }
            }
            if (state.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.cards.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp)) {
                        Text("还没有${state.kind.displayName}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (state.kind == ReusableCardKind.Opening) {
                                "新建一个预设，把会话模式、人物与卡片组合保存下来。"
                            } else {
                                "可以手动新建，也可以用右上角的生成按钮起草一张。"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(state.cards, key = ReusableCardDto::cardId) { card ->
                    ReusableCardRow(
                        kind = state.kind,
                        card = card,
                        deleting = state.deletingCardId == card.cardId,
                        onEdit = { viewModel.editCard(card) },
                        onDelete = { pendingDelete = card },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReusableCardRow(
    kind: ReusableCardKind,
    card: ReusableCardDto,
    deleting: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val titleKey = when (kind) {
        ReusableCardKind.Scene, ReusableCardKind.Opening -> "title"
        ReusableCardKind.Self -> "display_name"
    }
    val detailKeys = when (kind) {
        ReusableCardKind.Scene -> listOf("location", "atmosphere", "scene_drive")
        ReusableCardKind.Self -> listOf("scene_identity", "core_identity", "speech_style")
        ReusableCardKind.Opening -> listOf("note", "mode")
    }
    val title = card.preview[titleKey]?.jsonPrimitive?.contentOrNull
        ?: card.fields[titleKey]?.jsonPrimitive?.contentOrNull
        ?: kind.displayName
    val details = detailKeys.mapNotNull { key ->
        card.preview[key]?.jsonPrimitive?.contentOrNull
            ?: card.fields[key]?.jsonPrimitive?.contentOrNull
    }.filter(String::isNotBlank).joinToString(" · ")

    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !deleting, onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    details.ifBlank { "点按查看和编辑" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete, enabled = !deleting) {
                if (deleting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CardEditorDialog(
    state: CardLibraryUiState,
    onFieldChange: (String, String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val editing = state.draft.cardId.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "编辑${state.kind.displayName}" else "新建${state.kind.displayName}") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 570.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(definitions(state.kind), key = CardFieldDefinition::key) { field ->
                    when {
                        state.kind == ReusableCardKind.Opening && field.key == "mode" -> {
                            OpeningModeSelector(
                                selected = state.draft.values[field.key].orEmpty(),
                                enabled = !state.saving,
                                onSelected = { onFieldChange(field.key, it) },
                            )
                        }
                        state.kind == ReusableCardKind.Opening && field.key == "scene_card_id" -> {
                            ReusableCardSelector(
                                label = "场景卡",
                                kind = ReusableCardKind.Scene,
                                cards = state.sceneCards,
                                selectedId = state.draft.values[field.key].orEmpty(),
                                enabled = !state.saving,
                                onSelected = { onFieldChange(field.key, it) },
                            )
                        }
                        state.kind == ReusableCardKind.Opening && field.key == "self_card_id" -> {
                            ReusableCardSelector(
                                label = "自设卡",
                                kind = ReusableCardKind.Self,
                                cards = state.selfCards,
                                selectedId = state.draft.values[field.key].orEmpty(),
                                enabled = !state.saving,
                                onSelected = { onFieldChange(field.key, it) },
                            )
                        }
                        else -> OutlinedTextField(
                            value = state.draft.values[field.key].orEmpty(),
                            onValueChange = { onFieldChange(field.key, it) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.saving,
                            label = { Text(field.label + if (field.required) " *" else "") },
                            singleLine = field.singleLine,
                            minLines = if (field.singleLine) 1 else 2,
                            maxLines = if (field.singleLine) 1 else 5,
                        )
                    }
                }
                if (state.error.isNotBlank()) {
                    item { Text(state.error, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !state.saving) {
                if (state.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(6.dp))
                Text(if (state.saving) "保存中" else "保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("取消") } },
    )
}

@Composable
private fun OpeningModeSelector(
    selected: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("会话模式 *", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("observe" to "旁观", "act" to "扮演", "insert" to "入场").forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    enabled = enabled,
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun ReusableCardSelector(
    label: String,
    kind: ReusableCardKind,
    cards: List<ReusableCardDto>,
    selectedId: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = cards.firstOrNull { it.cardId == selectedId }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && cards.isNotEmpty(),
            ) {
                Text(
                    when {
                        selected != null -> selected.cardTitle(kind)
                        selectedId.isNotBlank() -> "原卡片已不存在"
                        cards.isEmpty() -> "资料库中还没有${kind.displayName}"
                        else -> "不使用${kind.displayName}"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("不使用${kind.displayName}") },
                    onClick = {
                        expanded = false
                        onSelected("")
                    },
                )
                cards.forEach { card ->
                    DropdownMenuItem(
                        text = { Text(card.cardTitle(kind), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            expanded = false
                            onSelected(card.cardId)
                        },
                    )
                }
            }
        }
    }
}

private fun ReusableCardDto.cardTitle(kind: ReusableCardKind): String {
    val key = if (kind == ReusableCardKind.Self) "display_name" else "title"
    return preview[key]?.jsonPrimitive?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?: fields[key]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
        ?: kind.displayName
}

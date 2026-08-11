package top.wkbin.zaomeng.feature.originalknowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import top.wkbin.zaomeng.data.api.OriginalKnowledgeEntryDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OriginalKnowledgeScreen(
    viewModel: OriginalKnowledgeViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingEntry by remember { mutableStateOf<OriginalKnowledgeEntryDto?>(null) }

    editingEntry?.let { entry ->
        BoundaryDialog(
            entry = entry,
            characters = state.characters,
            busy = state.busyEntryId == entry.sourceId,
            onDismiss = { editingEntry = null },
            onSave = { visibility, knowers ->
                editingEntry = null
                viewModel.saveBoundary(entry, visibility, knowers)
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("原文证据中心") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::rebuild, enabled = !state.rebuilding && state.busyEntryId.isBlank()) {
                        if (state.rebuilding) CircularProgressIndicator(strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = "重建原文证据索引")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "search") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("搜索、固定并校对角色可知边界", fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = state.query,
                                onValueChange = viewModel::updateQuery,
                                label = { Text("人物、事件或原文关键词") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = viewModel::search, enabled = !state.loading) {
                                Icon(Icons.Default.Search, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("搜索")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("只看固定证据", modifier = Modifier.weight(1f))
                            Switch(checked = state.pinnedOnly, onCheckedChange = viewModel::setPinnedOnly)
                        }
                    }
                }
            }
            if (state.error.isNotBlank() || state.message.isNotBlank()) {
                item(key = "notice") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (state.error.isNotBlank()) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(state.error.ifBlank { state.message }, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::dismissNotice) { Text("关闭") }
                        }
                    }
                }
            }
            when {
                state.loading -> item(key = "loading") {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.items.isEmpty() -> item(key = "empty") {
                    Text("没有找到匹配的原文证据。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> items(state.items, key = OriginalKnowledgeEntryDto::sourceId) { entry ->
                    EvidenceCard(
                        entry = entry,
                        busy = state.busyEntryId == entry.sourceId,
                        onTogglePinned = { viewModel.setPinned(entry, !entry.pinned) },
                        onEditBoundary = { editingEntry = entry },
                    )
                }
            }
        }
    }
}

@Composable
private fun EvidenceCard(
    entry: OriginalKnowledgeEntryDto,
    busy: Boolean,
    onTogglePinned: () -> Unit,
    onEditBoundary: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.title.ifBlank { entry.sourceId }, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${entry.sourceId} · 字符 ${entry.location.startChar}–${entry.location.endChar} · ${entry.visibility.visibilityLabel()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) CircularProgressIndicator(strokeWidth = 2.dp)
            }
            Text(entry.excerpt, style = MaterialTheme.typography.bodyMedium)
            if (entry.knowers.isNotEmpty()) {
                Text("知情角色：${entry.knowers.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
            }
            if (entry.characters.isNotEmpty()) {
                Text("片段涉及：${entry.characters.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onTogglePinned, enabled = !busy) {
                    Text(if (entry.pinned) "取消固定" else "固定证据")
                }
                TextButton(onClick = onEditBoundary, enabled = !busy) { Text("校对可知边界") }
            }
        }
    }
}

@Composable
private fun BoundaryDialog(
    entry: OriginalKnowledgeEntryDto,
    characters: List<String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit,
) {
    var visibility by remember(entry.sourceId) { mutableStateOf(entry.visibility) }
    var knowers by remember(entry.sourceId) { mutableStateOf(entry.knowers.toSet()) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("校对角色可知边界") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Text(entry.excerpt, maxLines = 5, overflow = TextOverflow.Ellipsis) }
                item { Text("可见范围", fontWeight = FontWeight.SemiBold) }
                items(VISIBILITIES) { option ->
                    FilterChip(
                        selected = visibility == option,
                        onClick = { visibility = option },
                        label = { Text(option.visibilityLabel()) },
                    )
                }
                if (visibility in setOf("private", "scene")) {
                    item { Text("知情角色", fontWeight = FontWeight.SemiBold) }
                    items(characters, key = { it }) { character ->
                        FilterChip(
                            selected = character in knowers,
                            onClick = {
                                knowers = if (character in knowers) knowers - character else knowers + character
                            },
                            label = { Text(character) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(visibility, knowers.toList()) },
                enabled = !busy && (visibility !in setOf("private", "scene") || knowers.isNotEmpty()),
            ) { Text("保存边界") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

private val VISIBILITIES = listOf("public", "scene", "private", "uncertain")

private fun String.visibilityLabel(): String = when (this) {
    "public" -> "公开信息"
    "scene" -> "同场角色可知"
    "private" -> "指定角色私密可知"
    else -> "边界不确定"
}

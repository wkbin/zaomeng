package top.wkbin.zaomeng.feature.crossover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CrossoverScreen(
    viewModel: CrossoverViewModel,
    showBackButton: Boolean = true,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.createdRunId) {
        if (state.createdRunId.isNotBlank()) {
            val runId = state.createdRunId
            viewModel.consumeCreatedRun()
            onCreated(runId)
        }
    }
    Scaffold(topBar = { TopAppBar(
        title = { Text("跨书卷共演 Beta") },
        navigationIcon = {
            if (showBackButton) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }
        },
    ) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("测试功能 · 结果可能不稳定", style = MaterialTheme.typography.titleMedium)
                        Text("人物表现、世界设定和关系演化仍可能偏离预期。创建时只复制人物资料快照，共演产生的聊天、记忆、关系和修正不会写回任何原书卷。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { OutlinedTextField(state.title, viewModel::updateTitle, Modifier.fillMaxWidth(), label = { Text("共演空间名称") }, singleLine = true) }
            item { OutlinedTextField(state.worldSetting, viewModel::updateWorldSetting, Modifier.fillMaxWidth(), label = { Text("独立世界设定（可选）") }, minLines = 3, maxLines = 6) }
            item { Text("选择人物 ${state.selected.size}/$CROSSOVER_MAX_PARTICIPANTS", style = MaterialTheme.typography.titleMedium) }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(state.runs, key = { it.runId }) { run ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(run.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val itemWidth = (maxWidth - 16.dp) / 3
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                maxItemsInEachRow = 3,
                            ) {
                                run.availableCharacters.forEach { character ->
                                    val choice = CrossoverChoice(run.runId, run.title, character)
                                    val checked = state.selected.any { it.runId == run.runId && it.character == character }
                                    val enabled = !state.creating && (checked || state.selected.size < CROSSOVER_MAX_PARTICIPANTS)
                                    Surface(
                                        modifier = Modifier
                                            .width(itemWidth)
                                            .height(48.dp)
                                            .clickable(enabled = enabled) { viewModel.toggle(choice) },
                                        shape = MaterialTheme.shapes.small,
                                        color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                        contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        border = BorderStroke(
                                            1.dp,
                                            if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        ),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            if (checked) Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp))
                                            Text(
                                                character,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.error.isNotBlank()) item { Text(state.error, color = MaterialTheme.colorScheme.error) }
            item {
                Button(viewModel::create, Modifier.fillMaxWidth(), enabled = !state.creating && state.title.isNotBlank() && state.selected.size in 2..CROSSOVER_MAX_PARTICIPANTS && state.selected.map { it.runId }.distinct().size >= 2) {
                    if (state.creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(if (state.creating) "正在创建独立空间..." else "创建共演空间", Modifier.padding(start = if (state.creating) 8.dp else 0.dp))
                }
            }
        }
    }
}

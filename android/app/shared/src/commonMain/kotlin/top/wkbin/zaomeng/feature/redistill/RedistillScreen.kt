package top.wkbin.zaomeng.feature.redistill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.data.api.RedistillSegmentDto
import org.koin.compose.koinInject
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.rememberDocumentPicker
import top.wkbin.zaomeng.platform.rememberNotificationPermissionRequester

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedistillScreen(
    viewModel: RedistillViewModel,
    onBack: () -> Unit,
    onStarted: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showEstimateConfirmation by remember { mutableStateOf(false) }
    val distillationForeground: DistillationForeground = koinInject()
    val documentPicker = rememberDocumentPicker { name, bytes ->
        viewModel.loadDocument(name, bytes)
    }
    val requestNotificationPermission = rememberNotificationPermissionRequester {
        viewModel.submit()
    }

    fun submitRedistill() {
        if (distillationForeground.hasNotificationPermission()) {
            viewModel.submit()
        } else {
            requestNotificationPermission()
        }
    }

    fun requestRedistill() {
        if (state.samplingPlan != null) {
            showEstimateConfirmation = true
        } else {
            submitRedistill()
        }
    }

    LaunchedEffect(state.completed) {
        if (state.completed) {
            onStarted()
            viewModel.consumeCompleted()
        }
    }

    LaunchedEffect(state.samplingPlan) {
        if (state.samplingPlan == null) showEstimateConfirmation = false
    }

    if (showEstimateConfirmation) state.samplingPlan?.let { plan ->
            AlertDialog(
                onDismissRequest = { showEstimateConfirmation = false },
                title = { Text("确认开始本轮蒸馏") },
                text = {
                    Text(
                        "本轮预计调用约 ${plan.totalCalls} 次模型，消耗 ${plan.tokenLow.readableCount()}–${plan.tokenHigh.readableCount()} tokens，预计耗时 ${plan.timeLowSeconds.readableDuration()}–${plan.timeHighSeconds.readableDuration()}。",
                    )
                },
                dismissButton = {
                    TextButton(onClick = { showEstimateConfirmation = false }) { Text("返回调整") }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showEstimateConfirmation = false
                            submitRedistill()
                        },
                    ) { Text("确认开始") }
                },
            )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("继续蒸馏") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    "可以沿用当前正文，换入新的 TXT 书段，或从原文中选择推荐片段。已有资料会作为基线继续补充。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = state.characters,
                    onValueChange = viewModel::updateCharacters,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("本轮人物") },
                    supportingText = { Text("可新增人物，用逗号、顿号或换行分隔。") },
                    minLines = 2,
                )
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("换入新书段", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(
                            onClick = { documentPicker() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.submitting && !state.readingFile,
                        ) {
                            if (state.readingFile) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.UploadFile, contentDescription = null)
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(
                                when {
                                    state.readingFile -> "正在读取…"
                                    state.fileName.isBlank() -> "选择 TXT"
                                    else -> state.fileName
                                },
                            )
                        }
                        if (state.fileName.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${state.sourceCharCount.readableCount()} 字 · ${state.sourceSentenceCount} 句",
                                    Modifier.weight(1f),
                                )
                                TextButton(onClick = viewModel::clearFile) {
                                    Text("移除", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            item {
                RecommendationHeader(state = state, viewModel = viewModel)
            }
            state.suggestions?.let { suggestions ->
                if (suggestions.weakFieldLabels.isNotEmpty()) {
                    item {
                        Text(
                            "建议优先补充：${suggestions.weakFieldLabels.joinToString("、")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(suggestions.segments, key = RedistillSegmentDto::segmentId) { segment ->
                    SegmentCard(
                        segment = segment,
                        selected = state.selectedSegmentId == segment.segmentId,
                        onSelect = { viewModel.selectSegment(segment.segmentId) },
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.maxSentences,
                        onValueChange = viewModel::updateMaxSentences,
                        modifier = Modifier.weight(1f),
                        label = { Text("每批句数") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.maxChars,
                        onValueChange = viewModel::updateMaxChars,
                        modifier = Modifier.weight(1f),
                        label = { Text("每批字符") },
                        singleLine = true,
                    )
                }
            }
            item {
                RedistillSamplingPlanCard(
                    state = state,
                    onRetry = viewModel::refreshSamplingEstimate,
                )
            }
            if (state.error.isNotBlank()) {
                item { Text(state.error, color = MaterialTheme.colorScheme.error) }
            }
            item {
                Button(
                    onClick = ::requestRedistill,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.submitting,
                ) {
                    if (state.submitting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(if (state.submitting) "正在启动…" else "开始这一轮蒸馏")
                }
            }
        }
    }
}

@Composable
private fun RedistillSamplingPlanCard(
    state: RedistillUiState,
    onRetry: () -> Unit,
) {
    when {
        state.estimatingSampling -> Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("正在估算本轮蒸馏…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        state.samplingPlan != null -> state.samplingPlan.let { plan ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("本轮蒸馏预估", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "约 ${plan.totalCalls} 次模型调用 · ${plan.tokenLow.readableCount()}–${plan.tokenHigh.readableCount()} tokens",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "预计 ${plan.timeLowSeconds.readableDuration()}–${plan.timeHighSeconds.readableDuration()}；推荐 ${plan.suggestedMaxSentences} 句 / ${plan.suggestedMaxChars.readableCount()} 字取样",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        state.samplingEstimateError.isNotBlank() -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.samplingEstimateError,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun RecommendationHeader(state: RedistillUiState, viewModel: RedistillViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val characters = state.characters
        .split(',', '，', '、', ';', '；', '\n')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("从原文推荐片段", style = MaterialTheme.typography.titleMedium)
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(state.recommendationCharacter.ifBlank { "选择人物" }, Modifier.weight(1f))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    characters.forEach { character ->
                        DropdownMenuItem(
                            text = { Text(character) },
                            onClick = {
                                expanded = false
                                viewModel.selectRecommendationCharacter(character)
                            },
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = viewModel::recommendSegments,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.recommendationCharacter.isNotBlank() && !state.recommending,
            ) {
                if (state.recommending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (state.recommending) "正在挑选…" else "推荐三段正文")
            }
        }
    }
}

@Composable
private fun SegmentCard(segment: RedistillSegmentDto, selected: Boolean, onSelect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null)
                Text(
                    "第 ${segment.startSentence}–${segment.endSentence} 句",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(segment.preview)
            Text(
                listOf(segment.reason, segment.estimatedFieldLabels.joinToString("、"))
                    .filter(String::isNotBlank)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Int.readableCount(): String = when {
    this >= 10_000 -> "%.1f 万".format(this / 10_000f)
    else -> toString()
}

private fun Int.readableDuration(): String {
    val minutes = coerceAtLeast(0) / 60
    val seconds = coerceAtLeast(0) % 60
    return when {
        minutes == 0 -> "${seconds}秒"
        seconds == 0 || minutes >= 10 -> "${minutes}分钟"
        else -> "${minutes}分${seconds}秒"
    }
}

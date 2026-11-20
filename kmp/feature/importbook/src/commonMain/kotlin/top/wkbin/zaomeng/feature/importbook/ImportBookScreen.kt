package top.wkbin.zaomeng.feature.importbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.koin.compose.koinInject
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.formatOneDecimal
import top.wkbin.zaomeng.platform.rememberDocumentPicker
import top.wkbin.zaomeng.platform.rememberNotificationPermissionRequester

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportBookScreen(
    viewModel: ImportBookViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOnlineLibrary: () -> Unit,
    onRunCreated: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var requestedKindName by rememberSaveable { mutableStateOf("") }
    var showEstimateConfirmation by rememberSaveable { mutableStateOf(false) }
    val distillationForeground: DistillationForeground = koinInject()
    val documentPicker = rememberDocumentPicker(
        onCancelled = {
            // 用户取消选择或读取失败：必须复位 readingFile，否则导入入口会永久禁用。
            requestedKindName = ""
            viewModel.cancelFileSelection()
        },
    ) { name, bytes ->
        val kind = ImportDocumentKind.entries.firstOrNull { it.name == requestedKindName }
        requestedKindName = ""
        if (kind != null) {
            viewModel.loadDocument(name, bytes, kind)
        } else {
            viewModel.cancelFileSelection()
        }
    }
    val requestNotificationPermission = rememberNotificationPermissionRequester {
        viewModel.submit()
    }

    fun submitImport() {
        if (
            state.packageFile ||
            !state.autoDistill ||
            distillationForeground.hasNotificationPermission()
        ) {
            viewModel.submit()
        } else {
            requestNotificationPermission()
        }
    }

    fun requestImport() {
        if (!state.packageFile && state.autoDistill && state.samplingPlan != null) {
            showEstimateConfirmation = true
        } else {
            submitImport()
        }
    }

    fun chooseDocument(kind: ImportDocumentKind) {
        requestedKindName = kind.name
        viewModel.beginFileSelection()
        documentPicker()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshModelConfiguration()
    }

    LaunchedEffect(state.createdRunId) {
        if (state.createdRunId.isNotBlank()) {
            val runId = state.createdRunId
            viewModel.consumeCreatedRun()
            onRunCreated(runId)
        }
    }

    LaunchedEffect(state.samplingPlan) {
        if (state.samplingPlan == null) showEstimateConfirmation = false
    }

    if (showEstimateConfirmation) state.samplingPlan?.let { plan ->
            AlertDialog(
                onDismissRequest = { showEstimateConfirmation = false },
                title = { Text("确认开始蒸馏") },
                text = {
                    Text(
                        "本次预计调用约 ${plan.totalCalls} 次模型，消耗 ${plan.tokenLow.readableCount()}–${plan.tokenHigh.readableCount()} tokens，预计耗时 ${plan.timeLowSeconds.readableDuration()}–${plan.timeHighSeconds.readableDuration()}。",
                    )
                },
                dismissButton = {
                    TextButton(onClick = { showEstimateConfirmation = false }) { Text("再检查一下") }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showEstimateConfirmation = false
                            submitImport()
                        },
                    ) { Text("确认开始") }
                },
            )
    }

    val hasDocument = state.fileName.isNotBlank()
    val worldBook = state.documentKind == ImportDocumentKind.WorldBook
    val needsPreview = state.documentKind == ImportDocumentKind.CharacterCard || worldBook
    val canSubmit = hasDocument && !state.submitting && !state.readingFile &&
        (state.packageFile || !state.autoDistill || state.modelConfigured == true) &&
        (!needsPreview || (!state.previewBusy && state.sillyTavernPreview != null)) &&
        (!worldBook || state.targetRunId.isNotBlank())

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("导入") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (hasDocument) {
                        TextButton(
                            onClick = viewModel::clearDocument,
                            enabled = !state.submitting && !state.readingFile,
                        ) { Text("更换来源") }
                    }
                },
            )
        },
        bottomBar = {
            if (hasDocument) {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        if (state.error.isNotBlank()) {
                            Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (worldBook && state.targetRunId.isBlank() && state.worldBookTargets.isNotEmpty()) {
                            Text(
                                "请先选择目标书卷",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = ::requestImport,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canSubmit,
                        ) {
                            if (state.submitting) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                            Text(
                                when {
                                    state.submitting -> "处理中…"
                                    state.documentKind == ImportDocumentKind.CharacterCard -> "导入角色卡"
                                    worldBook -> "挂载世界书"
                                    state.packageFile -> "导入书卷包"
                                    state.autoDistill -> "导入并开始蒸馏"
                                    else -> "导入到书架"
                                },
                                modifier = if (state.submitting) Modifier.padding(start = 10.dp) else Modifier,
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        val listState = rememberLazyListState()
        LaunchedEffect(state.documentKind, state.fileName) { listState.scrollToItem(0) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(innerPadding).selectableGroup(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val canChooseSource = !state.readingFile
            if (!hasDocument) {
                item {
                    Column {
                        ImportSourceRow("小说", "TXT / EPUB", Icons.Outlined.Description, canChooseSource) {
                            chooseDocument(ImportDocumentKind.NovelText)
                        }
                        ImportSourceRow("造梦书卷包", ".zaomeng-run.zip", Icons.Outlined.Archive, canChooseSource) {
                            chooseDocument(ImportDocumentKind.RunPackage)
                        }
                        ImportSourceRow("SillyTavern 角色卡", "JSON / PNG", Icons.Outlined.Badge, canChooseSource) {
                            chooseDocument(ImportDocumentKind.CharacterCard)
                        }
                        ImportSourceRow("SillyTavern 世界书", "JSON · 挂载到已有书卷", Icons.AutoMirrored.Outlined.MenuBook, canChooseSource) {
                            chooseDocument(ImportDocumentKind.WorldBook)
                        }
                        ImportSourceRow(
                            "在线书卷包", "浏览并下载已蒸馏的书卷", Icons.Outlined.CloudDownload,
                            canChooseSource, onOpenOnlineLibrary,
                        )
                    }
                }
            }
            if (state.readingFile) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在读取文件…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.fileName.isNotBlank()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(state.fileName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                when (state.documentKind) {
                                    ImportDocumentKind.RunPackage -> "造梦书卷包 · ${state.fileSize.readableFileSize()}"
                                    ImportDocumentKind.CharacterCard -> "SillyTavern 角色卡 · ${state.fileSize.readableFileSize()}"
                                    ImportDocumentKind.WorldBook -> "SillyTavern 世界书 · ${state.fileSize.readableFileSize()}"
                                    else -> "正文 · ${state.charCount.readableCount()} 字 · ${state.sentenceCount} 句 · ${state.sourceEncoding}"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (state.documentKind == ImportDocumentKind.WorldBook && state.worldBookTargets.isNotEmpty()) {
                item { Text("选择目标书卷。挂载后将替换该书卷已有的独立世界书。") }
                items(state.worldBookTargets, key = { it.runId }) { run ->
                    val selected = state.targetRunId == run.runId
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                            selected = selected,
                            enabled = !state.submitting,
                            role = Role.RadioButton,
                            onClick = { viewModel.selectWorldBookTarget(run.runId) },
                        ).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = selected, onClick = null, enabled = !state.submitting)
                        Text(run.title.ifBlank { run.runId })
                    }
                }
            }
            if (needsPreview && !state.readingFile) {
                item {
                    SillyTavernPreview(state, viewModel::updateTestText, viewModel::refreshSillyTavernPreview)
                }
            }
            if (state.documentKind == ImportDocumentKind.NovelText) {
                if (state.autoDistill) {
                    item {
                        ModelConfigurationNotice(
                            configured = state.modelConfigured,
                            error = state.modelConfigurationError,
                            onOpenSettings = onOpenSettings,
                            onRetry = viewModel::refreshModelConfiguration,
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("导入后立即蒸馏", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (state.autoDistill) {
                                    "建立书卷后立即在后台处理，并显示进度通知。"
                                } else {
                                    "先保存到书架，之后再从书卷详情开始蒸馏。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.autoDistill,
                            onCheckedChange = viewModel::updateAutoDistill,
                            enabled = !state.submitting,
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = state.characters,
                        onValueChange = viewModel::updateCharacters,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("要蒸馏的人物") },
                        placeholder = { Text("例如：林黛玉，贾宝玉") },
                        supportingText = { Text("可用逗号、分号或换行分隔；会记住到下次导入。") },
                        minLines = 2,
                    )
                }
                item {
                    SamplingPlanCard(
                        state = state,
                        onRetry = viewModel::refreshSamplingEstimate,
                    )
                }
                item {
                    OutlinedButton(
                        onClick = viewModel::toggleAdvancedSampling,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.submitting,
                    ) {
                        Text(if (state.advancedSamplingVisible) "收起高级取样" else "高级取样")
                    }
                }
                if (state.advancedSamplingVisible) {
                    item {
                        OutlinedTextField(
                            value = state.maxSentences,
                            onValueChange = viewModel::updateMaxSentences,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("每批最多句数") },
                            supportingText = { Text("20–300") },
                            singleLine = true,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = state.maxChars,
                            onValueChange = viewModel::updateMaxChars,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("每批最多字符") },
                            supportingText = { Text("2000–200000") },
                            singleLine = true,
                        )
                    }
                }
                item {
                    Text(
                        if (state.autoDistill) {
                            "提交后会在后台蒸馏；可以离开详情页，进度会继续保存在手机中。"
                        } else {
                            "本次只建立书卷，不会立即调用模型。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!hasDocument && state.error.isNotBlank()) {
                item { Text(state.error, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ImportSourceRow(
    title: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun ModelConfigurationNotice(
    configured: Boolean?,
    error: String,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    when (configured) {
        null -> Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("正在检查模型配置…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        true -> Text(
            "模型已配置，可以在手机内开始蒸馏。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        false -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("开始蒸馏前需要配置模型", style = MaterialTheme.typography.titleSmall)
                Text(
                    error.ifBlank { "书卷包可以直接恢复；TXT 或 EPUB 正文需要模型生成角色资料。" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenSettings) { Text("配置模型") }
                    if (error.isNotBlank()) {
                        OutlinedButton(onClick = onRetry) { Text("重新检查") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SamplingPlanCard(
    state: ImportBookUiState,
    onRetry: () -> Unit,
) {
    when {
        state.estimatingSampling -> Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("正在估算本次蒸馏…", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text("本次蒸馏预估", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "推荐取样 ${plan.suggestedMaxSentences} 句 / ${plan.suggestedMaxChars.readableCount()} 字",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (plan.distillChunkCount > 1) {
                            "每位人物约 ${plan.distillChunkCount} 块，含汇总 ${plan.distillCallsPerCharacter} 轮"
                        } else {
                            "每位人物约 1 轮"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "共约 ${plan.totalCalls} 次模型调用 · ${plan.tokenLow.readableCount()}–${plan.tokenHigh.readableCount()} tokens · ${plan.timeLowSeconds.readableDuration()}–${plan.timeHighSeconds.readableDuration()}",
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

private fun Long.readableFileSize(): String = when {
    this >= 1024 * 1024 -> "${formatOneDecimal(this / 1024.0 / 1024.0)} MB"
    this >= 1024 -> "${formatOneDecimal(this / 1024.0)} KB"
    else -> "$this B"
}

private fun Int.readableCount(): String = when {
    this >= 10_000 -> "${formatOneDecimal(this / 10_000.0)} 万"
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

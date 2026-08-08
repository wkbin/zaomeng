package top.wkbin.zaomeng.feature.importbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.koin.compose.koinInject
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.rememberDocumentPicker
import top.wkbin.zaomeng.platform.rememberNotificationPermissionRequester
import top.wkbin.zaomeng.data.api.BuiltinNovelDto

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
    val documentPicker = rememberDocumentPicker { name, bytes ->
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
        val mimeTypes = when (kind) {
            ImportDocumentKind.NovelText -> arrayOf(
                "text/plain",
                "text/*",
                "application/epub+zip",
                "application/octet-stream",
            )
            ImportDocumentKind.RunPackage -> arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
            )
        }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入小说") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("从文件建立书卷", style = MaterialTheme.typography.titleLarge)
                Text(
                    "选择 TXT 或 EPUB 正文开始人物蒸馏，或者恢复之前导出的书卷包。数据只会写入这台手机。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.builtinNovels.isNotEmpty()) {
                item {
                    Text("内置书卷", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "直接复制已经蒸馏好的示例，不需要再次调用模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.builtinNovels, key = BuiltinNovelDto::packageId) { novel ->
                    BuiltinNovelCard(
                        novel = novel,
                        busy = state.cloningBuiltinId == novel.packageId,
                        enabled = !state.submitting && state.cloningBuiltinId.isBlank(),
                        onImport = { viewModel.cloneBuiltinNovel(novel.packageId) },
                    )
                }
            }
            if (state.builtinError.isNotBlank()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(state.builtinError, color = MaterialTheme.colorScheme.onErrorContainer)
                            OutlinedButton(onClick = viewModel::refreshBuiltinNovels) {
                                Text("重试")
                            }
                        }
                    }
                }
            }
            item {
                ImportSourceCard(
                    title = "在线书卷包",
                    description = "浏览已蒸馏的书卷包，下载、校验后直接导入本机书架。",
                    actionLabel = "浏览在线书卷包",
                    icon = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
                    enabled = !state.submitting && !state.readingFile,
                    onClick = onOpenOnlineLibrary,
                )
            }
            item {
                ImportSourceCard(
                    title = "TXT / EPUB 小说",
                    description = "TXT 支持 UTF-8、UTF-16 和 GB18030；EPUB 会在手机本机提取正文。",
                    actionLabel = "选择 TXT 或 EPUB",
                    icon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                    enabled = !state.submitting && !state.readingFile,
                    onClick = { chooseDocument(ImportDocumentKind.NovelText) },
                )
            }
            item {
                ImportSourceCard(
                    title = "造梦书卷包",
                    description = "导入 .zaomeng-run.zip，恢复人物资料、关系和已有处理结果。",
                    actionLabel = "选择书卷包",
                    icon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                    enabled = !state.submitting && !state.readingFile,
                    onClick = { chooseDocument(ImportDocumentKind.RunPackage) },
                )
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
                                if (state.packageFile) {
                                    "造梦书卷包 · ${state.fileSize.readableFileSize()}"
                                } else {
                                    "正文 · ${state.charCount.readableCount()} 字 · ${state.sentenceCount} 句 · ${state.sourceEncoding}"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (!state.packageFile) {
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
            if (state.error.isNotBlank()) {
                item { Text(state.error, color = MaterialTheme.colorScheme.error) }
            }
            item {
                Button(
                    onClick = ::requestImport,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.fileName.isNotBlank() &&
                        !state.submitting &&
                        !state.readingFile &&
                        (state.packageFile || !state.autoDistill || state.modelConfigured == true),
                ) {
                    if (state.submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        when {
                            state.submitting -> "处理中…"
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
}

@Composable
private fun BuiltinNovelCard(
    novel: BuiltinNovelDto,
    busy: Boolean,
    enabled: Boolean,
    onImport: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(novel.title.ifBlank { novel.novelId.ifBlank { "未命名书卷" } }, style = MaterialTheme.typography.titleMedium)
            Text(
                buildList {
                    if (novel.characterCount > 0) add("${novel.characterCount} 位人物")
                    if (novel.hasRelationGraph) add("含关系图谱")
                    if (novel.status.isNotBlank()) add(novel.status)
                }.joinToString(" · ").ifBlank { "已预先整理的本地书卷" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && novel.packageId.isNotBlank(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Text("导入到书架", modifier = if (busy) Modifier.padding(start = 8.dp) else Modifier)
            }
        }
    }
}

@Composable
private fun ImportSourceCard(
    title: String,
    description: String,
    actionLabel: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                icon()
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
            ) {
                Text(actionLabel)
            }
        }
    }
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

        state.samplingPlan != null -> state.samplingPlan?.let { plan ->
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
    this >= 1024 * 1024 -> "%.1f MB".format(this / 1024f / 1024f)
    this >= 1024 -> "%.1f KB".format(this / 1024f)
    else -> "$this B"
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

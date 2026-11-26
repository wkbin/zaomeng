package top.wkbin.zaomeng.feature.importbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Surface
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Contacts
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.Folder
import io.github.suqi8.coui.kmp.icon.extended.Layers
import io.github.suqi8.coui.kmp.icon.extended.Notes
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import org.koin.compose.koinInject
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.formatOneDecimal
import top.wkbin.zaomeng.platform.rememberDocumentPicker
import top.wkbin.zaomeng.platform.rememberNotificationPermissionRequester
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiPageScaffold
import top.wkbin.zaomeng.ui.coui.CouiTextField
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.couiPageScroll
import top.wkbin.zaomeng.ui.coui.isWideWindow

@Composable
fun ImportBookScreen(
    viewModel: ImportBookViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOnlineLibrary: () -> Unit,
    onRunCreated: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preview = state.sillyTavernPreview
    val replacingWorldBook = state.documentKind == ImportDocumentKind.WorldBook && preview?.previousEntryCount != null
    var requestedKindName by rememberSaveable { mutableStateOf("") }
    var showEstimateConfirmation by rememberSaveable { mutableStateOf(false) }
    var showReplacementConfirmation by rememberSaveable(state.fileName, state.targetRunId, preview?.previousBookRevision) {
        mutableStateOf(false)
    }
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
        when {
            replacingWorldBook -> showReplacementConfirmation = true
            !state.packageFile && state.autoDistill && state.samplingPlan != null -> showEstimateConfirmation = true
            else -> submitImport()
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

    val hasDocument = state.fileName.isNotBlank()
    val worldBook = state.documentKind == ImportDocumentKind.WorldBook
    val needsPreview = state.documentKind == ImportDocumentKind.CharacterCard || worldBook
    val canSubmit = hasDocument && !state.submitting && !state.readingFile &&
        (state.packageFile || !state.autoDistill || state.modelConfigured == true) &&
        (!needsPreview || (!state.previewBusy && state.sillyTavernPreview != null)) &&
        (!worldBook || state.targetRunId.isNotBlank())

    CouiPageScaffold(
        title = "导入",
        topBarStyle = CouiTopBarStyle.Small,
        modifier = Modifier.imePadding(),
        onBack = onBack,
        wide = isWideWindow(),
        actions = {
            if (hasDocument) {
                TextButton(
                    text = "更换来源",
                    onClick = viewModel::clearDocument,
                    enabled = !state.submitting && !state.readingFile,
                    minWidth = 0.dp,
                    textStyle = COUITheme.textStyles.bodyM,
                )
            }
        },
        bottomBar = {
            if (hasDocument) {
                Surface(shadowElevation = 3.dp) {
                    Column(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        if (state.error.isNotBlank()) {
                            Text(
                                state.error,
                                style = COUITheme.textStyles.bodyXS,
                                color = COUITheme.colorScheme.error,
                            )
                        }
                        if (worldBook && state.targetRunId.isBlank() && state.worldBookTargets.isNotEmpty()) {
                            Text(
                                "请先选择目标书卷",
                                style = COUITheme.textStyles.bodyXS,
                                color = COUITheme.colorScheme.labelSecondary,
                            )
                        }
                        Button(
                            onClick = ::requestImport,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canSubmit,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            if (state.submitting) {
                                CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                            }
                            Text(
                                when {
                                    state.submitting -> "处理中…"
                                    state.documentKind == ImportDocumentKind.CharacterCard -> "导入角色卡"
                                    replacingWorldBook -> "替换世界书"
                                    worldBook -> "挂载世界书"
                                    state.packageFile -> "导入书卷包"
                                    state.autoDistill -> "导入并开始蒸馏"
                                    else -> "导入到书架"
                                },
                                style = COUITheme.textStyles.buttonM,
                                modifier = if (state.submitting) Modifier.padding(start = 10.dp) else Modifier,
                            )
                        }
                    }
                }
            }
        },
    ) { scope ->
        if (showEstimateConfirmation) state.samplingPlan?.let { plan ->
            CouiAlertDialog(
                onDismissRequest = { showEstimateConfirmation = false },
                title = "确认开始蒸馏",
                confirmText = "确认开始",
                onConfirm = {
                    showEstimateConfirmation = false
                    submitImport()
                },
                dismissText = "再检查一下",
                onDismiss = { showEstimateConfirmation = false },
                body = {
                    Text(
                        "本次预计调用约 ${plan.totalCalls} 次模型，消耗 ${plan.tokenLow.readableCount()}–${plan.tokenHigh.readableCount()} tokens，预计耗时 ${plan.timeLowSeconds.readableDuration()}–${plan.timeHighSeconds.readableDuration()}。",
                        style = COUITheme.textStyles.bodyM,
                    )
                },
            )
        }

        if (showReplacementConfirmation && replacingWorldBook && preview != null) {
            val target = state.worldBookTargets.firstOrNull { it.runId == state.targetRunId }?.title.orEmpty()
            val previousName = preview.previousBookName.orEmpty().ifBlank { "未命名世界书" }
            val incomingName = preview.bookName.ifBlank { "未命名世界书" }
            CouiAlertDialog(
                onDismissRequest = { showReplacementConfirmation = false },
                title = "替换独立世界书？",
                confirmText = "确认替换",
                onConfirm = {
                    showReplacementConfirmation = false
                    submitImport()
                },
                dismissText = "取消",
                onDismiss = { showReplacementConfirmation = false },
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("目标书卷「$target」", style = COUITheme.textStyles.bodyM)
                        Text("当前挂载「$previousName」 · ${preview.previousEntryCount} 条", style = COUITheme.textStyles.bodyM)
                        Text("替换为「$incomingName」 · ${preview.entryCount} 条", style = COUITheme.textStyles.bodyM)
                    }
                },
            )
        }

        val listState = rememberLazyListState()
        LaunchedEffect(state.documentKind, state.fileName) { listState.scrollToItem(0) }
        LazyColumn(
            state = listState,
            modifier = Modifier.couiPageScroll(scope).padding(horizontal = 20.dp).selectableGroup(),
            contentPadding = scope.contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val canChooseSource = !state.readingFile
            if (!hasDocument) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        CardListGroup {
                            item {
                                ImportSourceRow("小说", "TXT / EPUB", COUIIcons.Notes, canChooseSource) {
                                    chooseDocument(ImportDocumentKind.NovelText)
                                }
                            }
                            item {
                                ImportSourceRow("造梦书卷包", ".zaomeng-run.zip", COUIIcons.Folder, canChooseSource) {
                                    chooseDocument(ImportDocumentKind.RunPackage)
                                }
                            }
                            item {
                                ImportSourceRow("SillyTavern 角色卡", "JSON / PNG", COUIIcons.Contacts, canChooseSource) {
                                    chooseDocument(ImportDocumentKind.CharacterCard)
                                }
                            }
                            item {
                                ImportSourceRow(
                                    "SillyTavern 世界书", "JSON · 挂载到已有书卷",
                                    COUIIcons.Layers, canChooseSource,
                                ) {
                                    chooseDocument(ImportDocumentKind.WorldBook)
                                }
                            }
                            item {
                                ImportSourceRow(
                                    "在线书卷包", "浏览并下载已蒸馏的书卷", COUIIcons.Download,
                                    canChooseSource, onOpenOnlineLibrary,
                                )
                            }
                        }
                    }
                }
            }
            if (state.readingFile) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                        Text("正在读取文件…", style = COUITheme.textStyles.bodyM, color = COUITheme.colorScheme.labelSecondary)
                    }
                }
            }
            if (state.fileName.isNotBlank()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(state.fileName, style = COUITheme.textStyles.headlineXS)
                            Text(
                                when (state.documentKind) {
                                    ImportDocumentKind.RunPackage -> "造梦书卷包 · ${state.fileSize.readableFileSize()}"
                                    ImportDocumentKind.CharacterCard -> "SillyTavern 角色卡 · ${state.fileSize.readableFileSize()}"
                                    ImportDocumentKind.WorldBook -> "SillyTavern 世界书 · ${state.fileSize.readableFileSize()}"
                                    else -> "正文 · ${state.charCount.readableCount()} 字 · ${state.sentenceCount} 句 · ${state.sourceEncoding}"
                                },
                                style = COUITheme.textStyles.bodyM,
                                color = COUITheme.colorScheme.labelSecondary,
                            )
                        }
                    }
                }
            }
            if (state.documentKind == ImportDocumentKind.WorldBook && state.worldBookTargets.isNotEmpty()) {
                item { Text("选择目标书卷", style = COUITheme.textStyles.bodyM) }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        CardListGroup {
                            state.worldBookTargets.forEach { run ->
                                item(key = run.runId) {
                                    RadioButtonPreference(
                                        title = run.title.ifBlank { run.runId },
                                        selected = state.targetRunId == run.runId,
                                        onClick = { viewModel.selectWorldBookTarget(run.runId) },
                                        enabled = !state.submitting,
                                    )
                                }
                            }
                        }
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
                    SwitchPreference(
                        title = "导入后立即蒸馏",
                        summary = if (state.autoDistill) {
                            "建立书卷后立即在后台处理，并显示进度通知。"
                        } else {
                            "先保存到书架，之后再从书卷详情开始蒸馏。"
                        },
                        checked = state.autoDistill,
                        onCheckedChange = viewModel::updateAutoDistill,
                        enabled = !state.submitting,
                        insideMargin = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CouiTextField(
                            value = state.characters,
                            onValueChange = viewModel::updateCharacters,
                            modifier = Modifier.fillMaxWidth(),
                            label = "要蒸馏的人物（例如：林黛玉，贾宝玉）",
                            minLines = 2,
                        )
                        Text(
                            "可用逗号、分号或换行分隔；会记住到下次导入。",
                            style = COUITheme.textStyles.bodyXS,
                            color = COUITheme.colorScheme.labelSecondary,
                        )
                    }
                }
                item {
                    SamplingPlanCard(
                        state = state,
                        onRetry = viewModel::refreshSamplingEstimate,
                    )
                }
                item {
                    Button(
                        onClick = viewModel::toggleAdvancedSampling,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.submitting,
                    ) {
                        Text(
                            if (state.advancedSamplingVisible) "收起高级取样" else "高级取样",
                            style = COUITheme.textStyles.buttonM,
                        )
                    }
                }
                if (state.advancedSamplingVisible) {
                    item {
                        CouiTextField(
                            value = state.maxSentences,
                            onValueChange = viewModel::updateMaxSentences,
                            modifier = Modifier.fillMaxWidth(),
                            label = "每批最多句数（20–300）",
                            singleLine = true,
                        )
                    }
                    item {
                        CouiTextField(
                            value = state.maxChars,
                            onValueChange = viewModel::updateMaxChars,
                            modifier = Modifier.fillMaxWidth(),
                            label = "每批最多字符（2000–200000）",
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
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
            }
            if (!hasDocument && state.error.isNotBlank()) {
                item { Text(state.error, style = COUITheme.textStyles.bodyM, color = COUITheme.colorScheme.error) }
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
    ArrowPreference(
        title = title,
        summary = description,
        startAction = {
            Icon(
                icon,
                contentDescription = null,
                tint = COUITheme.colorScheme.primary,
            )
        },
        onClick = onClick,
        enabled = enabled,
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
            CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
            Text("正在检查模型配置…", style = COUITheme.textStyles.bodyM, color = COUITheme.colorScheme.labelSecondary)
        }

        true -> Text(
            "模型已配置，可以在手机内开始蒸馏。",
            style = COUITheme.textStyles.bodyXS,
            color = COUITheme.colorScheme.primary,
        )

        false -> Card(
            colors = CardDefaults.defaultColors(
                color = COUITheme.colorScheme.secondaryContainer,
                contentColor = COUITheme.colorScheme.labelPrimary,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("开始蒸馏前需要配置模型", style = COUITheme.textStyles.bodyM, fontWeight = FontWeight.Medium)
                Text(
                    error.ifBlank { "书卷包可以直接恢复；TXT 或 EPUB 正文需要模型生成角色资料。" },
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelPrimary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        minWidth = 0.dp,
                    ) {
                        Text("配置模型", style = COUITheme.textStyles.buttonM)
                    }
                    if (error.isNotBlank()) {
                        Button(onClick = onRetry, minWidth = 0.dp) {
                            Text("重新检查", style = COUITheme.textStyles.buttonM)
                        }
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
            CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
            Text("正在估算本次蒸馏…", style = COUITheme.textStyles.bodyM, color = COUITheme.colorScheme.labelSecondary)
        }

        state.samplingPlan != null -> state.samplingPlan.let { plan ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("本次蒸馏预估", style = COUITheme.textStyles.bodyM, fontWeight = FontWeight.Medium)
                    Text(
                        "推荐取样 ${plan.suggestedMaxSentences} 句 / ${plan.suggestedMaxChars.readableCount()} 字",
                        style = COUITheme.textStyles.bodyM,
                    )
                    Text(
                        if (plan.distillChunkCount > 1) {
                            "每位人物约 ${plan.distillChunkCount} 块，含汇总 ${plan.distillCallsPerCharacter} 轮"
                        } else {
                            "每位人物约 1 轮"
                        },
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                    Text(
                        "共约 ${plan.totalCalls} 次模型调用 · ${plan.tokenLow.readableCount()}–${plan.tokenHigh.readableCount()} tokens · ${plan.timeLowSeconds.readableDuration()}–${plan.timeHighSeconds.readableDuration()}",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
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
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.error,
            )
            TextButton(
                text = "重试",
                onClick = onRetry,
                minWidth = 0.dp,
                textStyle = COUITheme.textStyles.bodyM,
            )
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

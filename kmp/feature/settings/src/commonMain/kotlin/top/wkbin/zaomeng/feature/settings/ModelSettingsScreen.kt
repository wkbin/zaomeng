package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.EmptyState
import io.github.suqi8.coui.kmp.basic.EmptyStateSizeType
import io.github.suqi8.coui.kmp.basic.FloatingActionButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.ChevronForward
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.Hide
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.icon.extended.Show
import io.github.suqi8.coui.kmp.overlay.OverlayBottomSheet
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.data.api.ModelProfileDto
import top.wkbin.zaomeng.platform.ProviderLogoMark
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiPage
import top.wkbin.zaomeng.ui.coui.CouiPageScaffold
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.couiPageScroll
import top.wkbin.zaomeng.ui.coui.isWideWindow

@Composable
fun ModelSettingsScreen(
    viewModel: ModelProfilesViewModel,
    onBack: () -> Unit,
    onAddProfile: () -> Unit,
    onEditProfile: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshWhenResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    CouiPage(
        title = "模型档案",
        topBarStyle = CouiTopBarStyle.Small,
        onBack = onBack,
        wide = isWideWindow(),
        overlays = {
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                FloatingActionButton(onClick = onAddProfile) {
                    Icon(COUIIcons.Add, contentDescription = "新增模型档案")
                }
            }
        },
    ) {
        if (state.loading) {
            item { LoadingContent(Modifier.fillMaxWidth(), "正在读取模型档案…") }
        } else {
            if (state.error.isNotBlank() || state.message.isNotBlank()) {
                item { StatusMessage(state.error.ifBlank { state.message }, state.error.isNotBlank()) }
            }
            if (state.profiles.isEmpty()) {
                item { EmptyProfiles(onAddProfile) }
            } else {
                items(state.profiles, key = { it.profileId }) { profile ->
                    ModelProfileCard(
                        profile = profile,
                        active = profile.profileId == state.activeProfileId,
                        switching = profile.profileId == state.switchingProfileId,
                        onEdit = { onEditProfile(profile.profileId) },
                        onActivate = { viewModel.activate(profile) },
                    )
                }
            }
        }
    }
}

@Composable
fun ModelProfileEditorScreen(
    viewModel: ModelProfileEditorViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }
    var showCatalogSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showManualModelInput by remember { mutableStateOf(false) }
    var showMaxTokensSheet by remember { mutableStateOf(false) }
    var showReasoningEffortSheet by remember { mutableStateOf(false) }
    var maxTokensDraft by remember { mutableStateOf("") }
    val catalog = modelCatalogs.firstOrNull { it.id == state.selectedCatalogId }
    val canDiscoverModels = state.provider != "ollama"

    LaunchedEffect(state.completed) {
        if (state.completed) {
            onBack()
            viewModel.consumeCompleted()
        }
    }

    fun requestBack() {
        if (state.isDirty && !state.saving && !state.deleting) showDiscardDialog = true else onBack()
    }

    CouiPageScaffold(
        title = if (state.isNew) "新增模型档案" else "编辑模型档案",
        onBack = ::requestBack,
        wide = isWideWindow(),
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = viewModel::testConnection,
                    modifier = Modifier.weight(1f),
                    enabled = !state.loading && !state.saving && !state.testing && !state.detecting && !state.deleting,
                    // 用 COUI 的次要按钮底色；buttonColorsTransparent 是给图片上的浮层用的，
                    // 白字 + 半透明白底在浅色页面上等于看不见。
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    if (state.testing) ProgressIcon()
                    Text(if (state.testing) "测试中…" else "测试连接")
                }
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.weight(1f),
                    enabled = !state.loading && !state.saving && !state.testing && !state.detecting && !state.deleting,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    if (state.saving) ProgressIcon()
                    Text(if (state.saving) "保存中…" else if (state.isNew) "创建并启用" else "保存")
                }
            }
        },
    ) { scope ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.couiPageScroll(scope),
                contentPadding = scope.contentPadding,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (state.loading) {
                    item { LoadingContent(Modifier.fillMaxWidth(), "正在读取模型档案…") }
                } else {
                    if (state.error.isNotBlank() || state.message.isNotBlank()) {
                        item { StatusMessage(state.error.ifBlank { state.message }, state.error.isNotBlank()) }
                    }
                    item {
                        EditorSection("档案信息") {
                            EditorField(
                                value = state.profileName,
                                onValueChange = viewModel::updateProfileName,
                                label = "档案名称",
                                placeholder = "例如：DeepSeek 写作",
                            )
                        }
                    }
                    item {
                        EditorSection("模型服务") {
                            SelectionRow(
                                title = "服务商预设",
                                value = catalog?.title ?: "选择服务商预设",
                                leading = { catalog?.let { ProviderLogo(it.id) } },
                                onClick = { showCatalogSheet = true },
                            )
                            if (catalog?.id == "custom" || catalog == null) {
                                EditorField(
                                    value = state.provider,
                                    onValueChange = viewModel::updateProvider,
                                    label = "服务商类型",
                                    placeholder = "openai-compatible / openai / anthropic / ollama",
                                )
                            }
                            EditorField(
                                value = state.baseUrl,
                                onValueChange = viewModel::updateBaseUrl,
                                label = "接口地址",
                                placeholder = "留空使用服务商默认地址",
                                supportingText = if (state.provider == "ollama") "127.0.0.1 指向手机自身；电脑上的 Ollama 请填写局域网地址。" else null,
                            )
                            if (state.provider != "ollama") {
                                EditorField(
                                    value = state.apiKey,
                                    onValueChange = viewModel::updateApiKey,
                                    label = "API Key",
                                    placeholder = if (state.apiKeyConfigured) "已保存；留空继续沿用" else "输入模型服务密钥",
                                    supportingText = "公开模型列表可留空直接拉取。",
                                    password = !showApiKey,
                                    trailingIcon = {
                                        IconButton(onClick = { showApiKey = !showApiKey }) {
                                            Icon(
                                                if (showApiKey) COUIIcons.Hide else COUIIcons.Show,
                                                if (showApiKey) "隐藏 API Key" else "显示 API Key",
                                            )
                                        }
                                    },
                                )
                            }
                            val needsDiscovery = canDiscoverModels && catalog?.models.orEmpty().isEmpty() &&
                                state.discoveredModels.isEmpty()
                            val modelTitle = catalog?.models?.firstOrNull { it.id == state.model }?.title
                                ?: state.model.ifBlank { if (needsDiscovery) "拉取并选择" else "选择模型" }
                            SelectionRow(
                                title = "模型名称",
                                value = modelTitle,
                                onClick = {
                                    showModelSheet = true
                                    if (needsDiscovery) viewModel.discoverModels()
                                },
                            )
                            if (showManualModelInput) {
                                EditorField(state.model, viewModel::updateModel, "手动输入模型名称", "例如 gpt-5-mini")
                            }
                        }
                    }
                    if (supportsReasoningControls(state)) {
                        item {
                            EditorSection("模型推理") {
                                SelectionRow(
                                    title = "推理强度",
                                    subtitle = "选项取决于模型接口；没有“关闭”时，服务商不支持关闭推理。",
                                    value = reasoningEffortLabel(state.reasoningEffort),
                                    onClick = { showReasoningEffortSheet = true },
                                )
                            }
                        }
                    }
                    item {
                        EditorSection("高级设置") {
                            EditorField(
                                value = state.requestTimeoutSeconds,
                                onValueChange = viewModel::updateRequestTimeout,
                                label = "请求超时（秒）",
                                placeholder = "60",
                                supportingText = "适用于非流式模型请求和连接测试，可设为 1 到 300 秒。",
                                keyboardType = KeyboardType.Number,
                            )
                            SelectionRow(
                                title = "最大输出 Token",
                                subtitle = "0 表示不额外限制，范围为 0 到 16000。",
                                value = state.maxTokens.ifBlank { "0" },
                                onClick = {
                                    maxTokensDraft = state.maxTokens
                                    showMaxTokensSheet = true
                                },
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "模型兼容检测",
                                    style = COUITheme.textStyles.bodyL,
                                    color = COUITheme.colorScheme.labelPrimary,
                                )
                                Text(
                                    "实际请求接口，检测首字时间、SSE 分块、JSON/NDJSON、response_format、关闭推理和 token 参数。",
                                    style = COUITheme.textStyles.bodyS,
                                    color = COUITheme.colorScheme.labelSecondary,
                                )
                                Button(
                                    onClick = viewModel::detectCapabilities,
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !state.detecting && !state.testing && !state.saving,
                                    colors = ButtonDefaults.buttonColors(),
                                ) {
                                    if (state.detecting) ProgressIcon()
                                    Text(if (state.detecting) "检测中，可能需要数十秒…" else "一键检测模型能力")
                                }
                            }
                        }
                    }
                    state.capabilityReport?.let { report ->
                        item {
                            EditorSection("能力检测结果") {
                                Text(
                                    "首字符 ${report.ttftMs} ms · 完整 ${report.totalMs} ms",
                                    style = COUITheme.textStyles.bodyM,
                                    color = COUITheme.colorScheme.labelPrimary,
                                )
                                Text(
                                    "流式：${if (report.streamSupported) "支持" else "不支持"} · 真流式：${if (report.trueStreaming) "是" else "否"}",
                                    style = COUITheme.textStyles.bodyM,
                                    color = COUITheme.colorScheme.labelPrimary,
                                )
                                Text(
                                    "SSE ${report.sseChunkCount} 块 · ${report.sseChunkMinBytes}/${report.sseChunkAvgBytes}/${report.sseChunkMaxBytes} B（最小/平均/最大）",
                                    style = COUITheme.textStyles.bodyS,
                                    color = COUITheme.colorScheme.labelSecondary,
                                )
                                Text(
                                    "结构化遵循率 ${report.jsonNdjsonAdherence}% · response_format ${if (report.responseFormatSupported) "支持" else "不支持"}",
                                    style = COUITheme.textStyles.bodyM,
                                    color = COUITheme.colorScheme.labelPrimary,
                                )
                                Text(
                                    "关闭推理：${reasoningOffStatusLabel(report.reasoningOffStatus)}",
                                    style = COUITheme.textStyles.bodyM,
                                    color = COUITheme.colorScheme.labelPrimary,
                                )
                                Text(
                                    "建议：${report.recommendedMaxTokens} Token · ${reasoningEffortLabel(report.recommendedReasoningEffort)} · ${report.recommendedTokenParameter} · ${responseFormatModeLabel(report.recommendedResponseFormatMode)}",
                                    style = COUITheme.textStyles.bodyM,
                                    color = COUITheme.colorScheme.labelPrimary,
                                )
                                report.warnings.forEach { warning ->
                                    Text(
                                        "• $warning",
                                        style = COUITheme.textStyles.bodyS,
                                        color = COUITheme.colorScheme.error,
                                    )
                                }
                                Button(
                                    onClick = viewModel::applyCapabilityRecommendation,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColorsPrimary(),
                                ) {
                                    Text("应用推荐配置")
                                }
                            }
                        }
                    }
                    if (!state.isNew) {
                        item {
                            Button(
                                onClick = { showDeleteDialog = true },
                                enabled = state.profileCount > 1 && !state.deleting && !state.saving,
                                // 次要底色 + 错误色文字，既保留危险语义也不会在浅色页面上消失。
                                colors = ButtonDefaults.buttonColors(contentColor = COUITheme.colorScheme.error),
                            ) {
                                if (state.deleting) ProgressIcon()
                                Icon(
                                    COUIIcons.Delete,
                                    contentDescription = null,
                                    tint = COUITheme.colorScheme.error,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (state.deleting) "删除中…" else "删除模型档案",
                                    style = COUITheme.textStyles.buttonM,
                                    color = COUITheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

        if (showDiscardDialog) {
            CouiAlertDialog(
                onDismissRequest = { showDiscardDialog = false },
                title = "放弃未保存的修改？",
                confirmText = "放弃",
                onConfirm = onBack,
                dismissText = "继续编辑",
                onDismiss = { showDiscardDialog = false },
                body = {
                    Text(
                        "返回后，本次对模型档案的修改不会保存。",
                        style = COUITheme.textStyles.bodyM,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                },
            )
        }
        if (showDeleteDialog) {
            CouiAlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = "删除模型档案？",
                confirmText = "删除",
                onConfirm = {
                    showDeleteDialog = false
                    viewModel.delete()
                },
                dismissText = "取消",
                onDismiss = { showDeleteDialog = false },
                body = {
                    Text(
                        if (state.profileId == state.activeProfileId) "当前模型将切换到剩余档案中的第一项，此操作无法撤销。"
                        else "删除后将无法恢复此档案及其本地 API Key。",
                        style = COUITheme.textStyles.bodyM,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                },
            )
        }
        if (showCatalogSheet) {
            CatalogSelectionSheet(
                selectedCatalogId = state.selectedCatalogId,
                onDismiss = { showCatalogSheet = false },
                onSelect = {
                    viewModel.selectCatalog(it)
                    showManualModelInput = false
                    showCatalogSheet = false
                },
            )
        }
        if (showModelSheet) {
            val models = remember(catalog, state.discoveredModels, state.model) {
                val presetModels = catalog?.models.orEmpty()
                val presetById = presetModels.associateBy { it.id }
                buildList {
                    val current = state.model.trim()
                    if (current.isNotEmpty()) add(presetById[current] ?: ModelChoice(current, current))
                    state.discoveredModels.forEach { add(presetById[it] ?: ModelChoice(it, it)) }
                    addAll(presetModels)
                }.distinctBy { it.id }
            }
            ModelSelectionSheet(
                models = models,
                selectedModel = state.model.trim(),
                discovering = state.discoveringModels,
                discoveryError = state.modelDiscoveryError,
                onDiscover = if (canDiscoverModels) viewModel::discoverModels else null,
                onDismiss = { showModelSheet = false },
                onSelect = {
                    viewModel.updateModel(it)
                    showManualModelInput = false
                    showModelSheet = false
                },
                onManualInput = {
                    showManualModelInput = true
                    showModelSheet = false
                },
            )
        }
        if (showMaxTokensSheet) {
            MaxTokensSheet(
                value = maxTokensDraft,
                onValueChange = { maxTokensDraft = it.filter(Char::isDigit).take(5) },
                onDismiss = { showMaxTokensSheet = false },
                onConfirm = {
                    viewModel.updateMaxTokens(maxTokensDraft)
                    showMaxTokensSheet = false
                },
            )
        }
        if (showReasoningEffortSheet) {
            ReasoningEffortSheet(
                selected = state.reasoningEffort,
                options = modelReasoningEfforts(state.provider, state.baseUrl, state.model),
                onDismiss = { showReasoningEffortSheet = false },
                onSelect = {
                    viewModel.updateReasoningEffort(it)
                    showReasoningEffortSheet = false
                },
            )
        }
        }
    }
}

@Composable
private fun EmptyProfiles(onAddProfile: () -> Unit) {
    EmptyState(
        title = "暂无模型档案",
        subtitle = "添加一个模型服务后即可开始蒸馏和聊天。",
        actionText = "新增模型档案",
        onActionClick = onAddProfile,
        sizeType = EmptyStateSizeType.Small,
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
    )
}

@Composable
private fun ModelProfileCard(
    profile: ModelProfileDto,
    active: Boolean,
    switching: Boolean,
    onEdit: () -> Unit,
    onActivate: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onEdit,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProviderLogo(profileCatalogId(profile))
                Text(
                    profile.name.ifBlank { profile.model },
                    modifier = Modifier.weight(1f),
                    style = COUITheme.textStyles.bodyM,
                    color = COUITheme.colorScheme.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (active) {
                    Text(
                        "当前",
                        style = COUITheme.textStyles.buttonM,
                        color = COUITheme.colorScheme.primary,
                    )
                }
                Icon(COUIIcons.ChevronForward, contentDescription = "编辑")
            }
            Text(
                "${profile.provider} · ${profile.model}",
                style = COUITheme.textStyles.bodyS,
                color = COUITheme.colorScheme.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (profile.configured) "连接已配置" else "尚未完成配置",
                    modifier = Modifier.weight(1f),
                    style = COUITheme.textStyles.bodyS,
                    color = if (profile.configured) COUITheme.colorScheme.primary else COUITheme.colorScheme.error,
                )
                if (!active && profile.configured) {
                    TextButton(
                        text = if (switching) "切换中…" else "设为当前",
                        onClick = onActivate,
                        enabled = !switching,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionRow(
    title: String,
    value: String,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    BasicComponent(
        modifier = Modifier.fillMaxWidth(),
        title = title,
        summary = subtitle,
        startAction = leading,
        endActions = {
            Text(
                value,
                style = COUITheme.textStyles.bodyM,
                color = COUITheme.colorScheme.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                COUIIcons.ChevronForward,
                contentDescription = null,
                tint = COUITheme.colorScheme.labelSecondary,
            )
        },
        onClick = onClick,
    )
}

@Composable
private fun CatalogSelectionSheet(
    selectedCatalogId: String,
    onDismiss: () -> Unit,
    onSelect: (ModelCatalog) -> Unit,
) {
    OverlayBottomSheet(
        show = true,
        title = "选择服务商预设",
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item {
                Text(
                    "选择后将填入推荐的模型和接口地址。",
                    modifier = Modifier.padding(bottom = 12.dp),
                    style = COUITheme.textStyles.bodyS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
            items(modelCatalogs, key = { it.id }) { catalog ->
                SheetChoiceRow(
                    title = catalog.title,
                    selected = catalog.id == selectedCatalogId,
                    leading = { ProviderLogo(catalog.id) },
                    onClick = { onSelect(catalog) },
                )
            }
        }
    }
}

@Composable
private fun ModelSelectionSheet(
    models: List<ModelChoice>,
    selectedModel: String,
    discovering: Boolean,
    discoveryError: String,
    onDiscover: (() -> Unit)?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onManualInput: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filteredModels = remember(models, query) {
        val search = query.trim()
        models.filter { it.id.contains(search, ignoreCase = true) || it.title.contains(search, ignoreCase = true) }
    }
    OverlayBottomSheet(
        show = true,
        title = "选择模型",
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onDiscover != null) {
                        Button(
                            onClick = onDiscover,
                            enabled = !discovering,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(),
                        ) {
                            if (discovering) ProgressIcon()
                            Text(if (discovering) "正在拉取模型…" else "拉取模型列表")
                        }
                    }
                    if (discoveryError.isNotBlank()) StatusMessage(discoveryError, error = true)
                    if (models.isNotEmpty()) {
                        EditorField(query, { query = it }, "搜索模型", "模型名称或 ID")
                        if (filteredModels.isEmpty()) {
                            Text(
                                "没有匹配的模型。",
                                style = COUITheme.textStyles.bodyM,
                                color = COUITheme.colorScheme.labelSecondary,
                            )
                        }
                    }
                    TextButton(text = "手动输入模型名称", onClick = onManualInput)
                }
            }
            items(filteredModels, key = { it.id }) { model ->
                SheetChoiceRow(title = model.title, selected = model.id == selectedModel, onClick = { onSelect(model.id) })
            }
        }
    }
}

@Composable
private fun SheetChoiceRow(
    title: String,
    selected: Boolean,
    leading: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    BasicComponent(
        modifier = Modifier.fillMaxWidth(),
        title = title,
        startAction = leading,
        endActions = if (selected) {
            {
                Icon(
                    COUIIcons.Ok,
                    contentDescription = "已选择",
                    tint = COUITheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        onClick = onClick,
    )
}

@Composable
private fun MaxTokensSheet(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayBottomSheet(
        show = true,
        title = "最大输出 Token",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "填写 0 表示不额外限制，可输入 0 到 16000。",
                style = COUITheme.textStyles.bodyS,
                color = COUITheme.colorScheme.labelSecondary,
            )
            EditorField(value, onValueChange, "最大输出 Token", "0", keyboardType = KeyboardType.Number)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = "取消", onClick = onDismiss)
                Button(onClick = onConfirm, colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text("确认")
                }
            }
        }
    }
}

private fun supportsReasoningControls(state: ModelProfileEditorUiState): Boolean =
    modelReasoningEfforts(state.provider, state.baseUrl, state.model).size > 1

private fun reasoningEffortLabel(value: String): String = when (value) {
    "off" -> "关闭"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "极高"
    else -> "自动"
}

private fun reasoningOffStatusLabel(value: String): String = when (value) {
    "supported" -> "支持"
    "not_required" -> "非推理模型，无需关闭"
    "unsupported" -> "不支持"
    else -> "未知"
}

private fun responseFormatModeLabel(value: String): String = when (value) {
    "json_object" -> "原生 JSON 约束"
    "prompt_only" -> "提示词 JSON 约束"
    else -> "自动 JSON 约束"
}

@Composable
private fun ReasoningEffortSheet(
    selected: String,
    options: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    OverlayBottomSheet(
        show = true,
        title = "推理强度",
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item {
                Text(
                    "模型不支持所选档位时，连接测试会返回服务商的具体错误。",
                    modifier = Modifier.padding(bottom = 12.dp),
                    style = COUITheme.textStyles.bodyS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
            items(options, key = { it }) { effort ->
                SheetChoiceRow(
                    title = reasoningEffortLabel(effort),
                    selected = effort == selected,
                    onClick = { onSelect(effort) },
                )
            }
        }
    }
}

@Composable
private fun EditorSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 16.dp),
            style = COUITheme.textStyles.bodyS,
            color = COUITheme.colorScheme.labelSecondary,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            CardListGroup {
                item {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    supportingText: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = label,
            enabled = true,
            readOnly = false,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = trailingIcon,
        )
        val hint = supportingText ?: placeholder.takeIf { value.isBlank() }
        if (!hint.isNullOrBlank()) {
            Text(
                hint,
                modifier = Modifier.padding(horizontal = 4.dp),
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier, text: String) {
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text,
            style = COUITheme.textStyles.bodyM,
            color = COUITheme.colorScheme.labelSecondary,
        )
    }
}

@Composable
private fun StatusMessage(text: String, error: Boolean) {
    Text(
        text,
        style = COUITheme.textStyles.bodyS,
        color = if (error) COUITheme.colorScheme.error else COUITheme.colorScheme.primary,
    )
}

@Composable
private fun ProgressIcon() = CircularProgressIndicator(
    modifier = Modifier.size(18.dp).padding(end = 6.dp),
    strokeWidth = 2.dp,
)

private fun profileCatalogId(profile: ModelProfileDto): String =
    modelCatalogs.firstOrNull { catalog ->
        catalog.models.any { it.id == profile.model } && catalog.provider == profile.provider &&
            catalog.baseUrl.trimEnd('/') == profile.baseUrl.trimEnd('/')
    }?.id.orEmpty()

@Composable
private fun ProviderLogo(catalogId: String) {
    ProviderLogoMark(catalogId)
}

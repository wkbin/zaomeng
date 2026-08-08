package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import top.wkbin.zaomeng.platform.ProviderLogoMark
import top.wkbin.zaomeng.data.api.ModelProfileDto

@OptIn(ExperimentalMaterial3Api::class)
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型档案") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddProfile) {
                Icon(Icons.Default.Add, contentDescription = "新增模型档案")
            }
        },
    ) { innerPadding ->
        if (state.loading) {
            LoadingContent(Modifier.fillMaxSize().padding(innerPadding), "正在读取模型档案…")
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.error.isNotBlank() || state.message.isNotBlank()) {
                item { StatusMessage(state.error.ifBlank { state.message }, state.error.isNotBlank()) }
            }
            if (state.profiles.isEmpty()) {
                item {
                    EmptyProfiles(onAddProfile)
                }
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var showMaxTokensSheet by remember { mutableStateOf(false) }
    var showReasoningEffortSheet by remember { mutableStateOf(false) }
    var maxTokensDraft by remember { mutableStateOf("") }

    LaunchedEffect(state.completed) {
        if (state.completed) onBack()
    }

    fun requestBack() {
        if (state.isDirty && !state.saving && !state.deleting) showDiscardDialog = true else onBack()
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("返回后，本次对模型档案的修改不会保存。") },
            confirmButton = { TextButton(onClick = onBack) { Text("放弃") } },
            dismissButton = { TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") } },
        )
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除模型档案？") },
            text = {
                Text(
                    if (state.profileId == state.activeProfileId) "当前模型将切换到剩余档案中的第一项，此操作无法撤销。"
                    else "删除后将无法恢复此档案及其本地 API Key。",
                )
            },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; viewModel.delete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } },
        )
    }
    if (showCatalogSheet) {
        CatalogSelectionSheet(
            selectedCatalogId = state.selectedCatalogId,
            onDismiss = { showCatalogSheet = false },
            onSelect = {
                viewModel.selectCatalog(it)
                showCatalogSheet = false
            },
        )
    }
    if (showModelSheet) {
        val catalog = modelCatalogs.firstOrNull { it.id == state.selectedCatalogId }
        catalog?.takeIf { it.models.isNotEmpty() }?.let {
            ModelSelectionSheet(
                catalog = it,
                selectedModel = state.model,
                onDismiss = { showModelSheet = false },
                onSelect = {
                    viewModel.updateModel(it)
                    showModelSheet = false
                },
            )
        }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "新增模型档案" else "编辑模型档案") },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    modifier = Modifier.weight(1f),
                    enabled = !state.loading && !state.saving && !state.testing && !state.deleting,
                ) {
                    if (state.testing) ProgressIcon()
                    Text(if (state.testing) "测试中…" else "测试连接")
                }
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.weight(1f),
                    enabled = !state.loading && !state.saving && !state.testing && !state.deleting,
                ) {
                    if (state.saving) ProgressIcon()
                    Text(if (state.saving) "保存中…" else if (state.isNew) "创建并启用" else "保存")
                }
            }
        },
    ) { innerPadding ->
        if (state.loading) {
            LoadingContent(Modifier.fillMaxSize().padding(innerPadding), "正在读取模型档案…")
            return@Scaffold
        }
        val catalog = modelCatalogs.firstOrNull { it.id == state.selectedCatalogId }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (catalog?.id == "custom" || catalog == null) {
                        EditorField(
                            value = state.provider,
                            onValueChange = viewModel::updateProvider,
                            label = "服务商类型",
                            placeholder = "openai-compatible / openai / anthropic / ollama",
                        )
                    }
                    if (catalog != null && catalog.models.isNotEmpty()) {
                        val modelTitle = catalog.models.firstOrNull { it.id == state.model }?.title
                            ?: state.model.ifBlank { "选择模型" }
                        SelectionRow(
                            title = "模型名称",
                            value = modelTitle,
                            onClick = { showModelSheet = true },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    } else {
                        EditorField(state.model, viewModel::updateModel, "模型名称", "例如 deepseek-v4-flash / gpt-5-mini")
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
                            supportingText = "密钥由 Android Keystore 加密保护，不显示在档案列表或诊断信息中。",
                            password = !showApiKey,
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Icon(if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showApiKey) "隐藏 API Key" else "显示 API Key")
                                }
                            },
                        )
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
                    SelectionRow(
                        title = "最大输出 Token",
                        subtitle = "0 表示不额外限制，范围为 0 到 16000。",
                        value = state.maxTokens.ifBlank { "0" },
                        onClick = {
                            maxTokensDraft = state.maxTokens
                            showMaxTokensSheet = true
                        },
                    )
                }
            }
            if (!state.isNew) {
                item {
                    TextButton(
                        onClick = { showDeleteDialog = true },
                        enabled = state.profileCount > 1 && !state.deleting && !state.saving,
                    ) {
                        if (state.deleting) ProgressIcon()
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (state.deleting) "删除中…" else "删除模型档案",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyProfiles(onAddProfile: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("暂无模型档案", style = MaterialTheme.typography.titleMedium)
        Text("添加一个模型服务后即可开始蒸馏和聊天。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onAddProfile) { Text("新增模型档案") }
    }
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
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.clickable(onClick = onEdit).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProviderLogo(profileCatalogId(profile))
                Text(profile.name.ifBlank { profile.model }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (active) Text("当前", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "编辑")
            }
            Text("${profile.provider} · ${profile.model}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (profile.configured) "连接已配置" else "尚未完成配置", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = if (profile.configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                if (!active && profile.configured) {
                    TextButton(onClick = onActivate, enabled = !switching) {
                        if (switching) ProgressIcon()
                        Text(if (switching) "切换中…" else "设为当前")
                    }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogSelectionSheet(
    selectedCatalogId: String,
    onDismiss: () -> Unit,
    onSelect: (ModelCatalog) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item {
                Text("选择服务商预设", style = MaterialTheme.typography.titleLarge)
                Text("选择后将填入推荐的模型和接口地址。", modifier = Modifier.padding(top = 4.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectionSheet(
    catalog: ModelCatalog,
    selectedModel: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item {
                Text("选择模型", style = MaterialTheme.typography.titleLarge)
                Text(catalog.title, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(catalog.models, key = { it.id }) { model ->
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (selected) Icon(Icons.Default.Check, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaxTokensSheet(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("最大输出 Token", style = MaterialTheme.typography.titleLarge)
                Text("填写 0 表示不额外限制，可输入 0 到 16000。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EditorField(value, onValueChange, "最大输出 Token", "0", keyboardType = KeyboardType.Number)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = onConfirm) { Text("确认") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasoningEffortSheet(
    selected: String,
    options: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item {
                Text("推理强度", style = MaterialTheme.typography.titleLarge)
                Text(
                    "模型不支持所选档位时，连接测试会返回服务商的具体错误。",
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(
                options,
                key = { it },
            ) { effort ->
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
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
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
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = trailingIcon,
        singleLine = true,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    )
}

@Composable
private fun LoadingContent(modifier: Modifier, text: String) {
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(text)
    }
}

@Composable
private fun StatusMessage(text: String, error: Boolean) {
    Text(text, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ProgressIcon() = CircularProgressIndicator(Modifier.size(18.dp).padding(end = 6.dp), strokeWidth = 2.dp)

private fun profileCatalogId(profile: ModelProfileDto): String =
    modelCatalogs.firstOrNull { catalog ->
        catalog.models.any { it.id == profile.model } && catalog.provider == profile.provider &&
            catalog.baseUrl.trimEnd('/') == profile.baseUrl.trimEnd('/')
    }?.id.orEmpty()

@Composable
private fun ProviderLogo(catalogId: String) {
    ProviderLogoMark(catalogId)
}

@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val modifier = if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leadingIcon?.let { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


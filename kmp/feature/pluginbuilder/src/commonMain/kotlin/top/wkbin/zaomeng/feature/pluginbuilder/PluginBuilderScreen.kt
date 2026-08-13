package top.wkbin.zaomeng.feature.pluginbuilder

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.zaomeng.data.api.PluginBuilderActionMode
import top.wkbin.zaomeng.data.api.PluginBuilderPermissionDto
import top.wkbin.zaomeng.data.api.PluginBuilderSettingDraft
import top.wkbin.zaomeng.data.api.PluginBuilderSettingType
import top.wkbin.zaomeng.data.api.PluginBuilderTemplate
import top.wkbin.zaomeng.data.api.PluginBuilderValidationDto
import top.wkbin.zaomeng.platform.rememberFileExporter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginBuilderScreen(
    viewModel: PluginBuilderViewModel = koinViewModel(),
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showManifest by remember { mutableStateOf(false) }
    val fileExporter = rememberFileExporter(
        onSave = viewModel::savePendingExport,
        onCancelled = viewModel::cancelExportDestination,
    )

    LaunchedEffect(state.exportRequestId, state.exportDestinationPending) {
        if (!state.exportDestinationPending || state.pendingExportFilename.isBlank()) return@LaunchedEffect
        viewModel.consumeExportDestinationRequest(state.exportRequestId)
        fileExporter(state.pendingExportFilename, "application/zip")
    }

    if (showManifest) {
        ManifestDialog(
            json = state.validation?.manifestJson.orEmpty(),
            onDismiss = { showManifest = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件工坊") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 920.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BuilderIntroCard()

                SectionCard("1. 选择插件能力", "先从一种能力开始，权限会随能力自动生成。") {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TemplateChip("快捷动作", PluginBuilderTemplate.ChatAction, state.draft.template, viewModel::updateTemplate)
                        TemplateChip("生成增强", PluginBuilderTemplate.GenerationEnhancer, state.draft.template, viewModel::updateTemplate)
                        TemplateChip("临时 NPC", PluginBuilderTemplate.TemporaryNpc, state.draft.template, viewModel::updateTemplate)
                    }
                }

                SectionCard("2. 基本信息", "插件 ID 会根据名称自动生成，不需要手写。") {
                    OutlinedTextField(
                        value = state.draft.name,
                        onValueChange = viewModel::updateName,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("插件名称") },
                        placeholder = { Text("例如：温柔接话") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.draft.id,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("自动生成的插件 ID") },
                        supportingText = { Text("用于安装和升级；相同 ID 会被识别为同一个插件。") },
                        readOnly = true,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.draft.version,
                        onValueChange = viewModel::updateVersion,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("版本") },
                        placeholder = { Text("0.1.0") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.draft.description,
                        onValueChange = viewModel::updateDescription,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("一句话介绍（可选）") },
                        minLines = 2,
                    )
                }

                SectionCard("3. 描述它要做什么", "只写自然语言；变量标签可把用户草稿或设置值带进提示词。") {
                    OutlinedTextField(
                        value = state.draft.title,
                        onValueChange = viewModel::updateTitle,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("在聊天中显示的名称") },
                        singleLine = true,
                    )
                    if (state.draft.template == PluginBuilderTemplate.ChatAction) {
                        Text("生成方式", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.draft.actionMode == PluginBuilderActionMode.Suggest,
                                onClick = { viewModel.updateActionMode(PluginBuilderActionMode.Suggest) },
                                label = { Text("生成一条") },
                            )
                            FilterChip(
                                selected = state.draft.actionMode == PluginBuilderActionMode.Variants,
                                onClick = { viewModel.updateActionMode(PluginBuilderActionMode.Variants) },
                                label = { Text("生成多个候选") },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = state.draft.prompt,
                        onValueChange = viewModel::updatePrompt,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("自然语言提示词") },
                        minLines = 6,
                        maxLines = 12,
                    )
                    Text("点击插入变量", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.draft.template == PluginBuilderTemplate.ChatAction) {
                            AssistChip(
                                onClick = { viewModel.insertVariable("{{seed_text}}") },
                                label = { Text("用户草稿 {{seed_text}}") },
                            )
                        }
                        state.draft.settings.forEach { setting ->
                            AssistChip(
                                onClick = { viewModel.insertVariable("{{config.${setting.key}}}") },
                                label = { Text("${setting.title} {{config.${setting.key}}}") },
                            )
                        }
                    }
                }

                SettingsSection(
                    settings = state.draft.settings,
                    onAdd = viewModel::addSetting,
                    onRemove = viewModel::removeSetting,
                    onTitleChange = viewModel::updateSettingTitle,
                    onTypeChange = viewModel::updateSettingType,
                    onDefaultChange = viewModel::updateSettingDefault,
                    onOptionsChange = viewModel::updateSettingOptions,
                )

                ValidationSection(state.validation, state.validating)

                if (state.error.isNotBlank()) {
                    StatusCard(state.error, error = true)
                }
                if (state.message.isNotBlank()) {
                    StatusCard(state.message, error = false)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = viewModel::installForTesting,
                        enabled = state.validation?.valid == true && !state.validating && !state.working,
                    ) {
                        if (state.working) {
                            CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("安装到本机试用")
                    }
                    OutlinedButton(
                        onClick = viewModel::prepareExport,
                        enabled = state.validation?.valid == true && !state.validating && !state.working,
                    ) { Text("导出分享") }
                    TextButton(
                        onClick = { showManifest = true },
                        enabled = state.validation?.manifestJson?.isNotBlank() == true,
                    ) { Text("查看 plugin.json") }
                }
                Text(
                    "安装或导出的插件只包含声明式 JSON 和说明文件，不会携带或执行 Python、JAR、JavaScript 等任意代码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BuilderIntroCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Outlined.Extension, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("不用写代码，也能扩展造梦", fontWeight = FontWeight.SemiBold)
                Text(
                    "填写名称和提示词，插件工坊会自动生成 ID、权限和 Plugin API 2 清单，并在导出前由真实运行时检查。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun TemplateChip(
    label: String,
    value: PluginBuilderTemplate,
    selected: PluginBuilderTemplate,
    onSelect: (PluginBuilderTemplate) -> Unit,
) {
    FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
}

@Composable
private fun SettingsSection(
    settings: List<PluginBuilderSettingDraft>,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onTitleChange: (Int, String) -> Unit,
    onTypeChange: (Int, PluginBuilderSettingType) -> Unit,
    onDefaultChange: (Int, String) -> Unit,
    onOptionsChange: (Int, String) -> Unit,
) {
    SectionCard("4. 可调设置（可选）", "添加后，用户可以在插件页调整这些值，并通过变量标签写入提示词。") {
        if (settings.isEmpty()) {
            Text("当前没有设置项。简单插件可以直接跳过。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        settings.forEachIndexed { index, setting ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("设置 ${index + 1}", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { onRemove(index) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除设置")
                        }
                    }
                    OutlinedTextField(
                        value = setting.title,
                        onValueChange = { onTitleChange(index, it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("设置名称") },
                        supportingText = { Text("变量：{{config.${setting.key}}}") },
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingTypeChip("开关", PluginBuilderSettingType.Boolean, setting.type) { onTypeChange(index, it) }
                        SettingTypeChip("整数", PluginBuilderSettingType.Integer, setting.type) { onTypeChange(index, it) }
                        SettingTypeChip("选项", PluginBuilderSettingType.Enum, setting.type) { onTypeChange(index, it) }
                    }
                    when (setting.type) {
                        PluginBuilderSettingType.Boolean -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = setting.defaultValue == "false",
                                onClick = { onDefaultChange(index, "false") },
                                label = { Text("默认关闭") },
                            )
                            FilterChip(
                                selected = setting.defaultValue == "true",
                                onClick = { onDefaultChange(index, "true") },
                                label = { Text("默认开启") },
                            )
                        }
                        PluginBuilderSettingType.Integer -> OutlinedTextField(
                            value = setting.defaultValue,
                            onValueChange = { onDefaultChange(index, it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("默认整数") },
                            singleLine = true,
                        )
                        PluginBuilderSettingType.Enum -> {
                            var optionsText by remember(setting.key) {
                                mutableStateOf(setting.options.joinToString("，"))
                            }
                            OutlinedTextField(
                                value = optionsText,
                                onValueChange = {
                                    optionsText = it
                                    onOptionsChange(index, it)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("候选项（逗号分隔）") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = setting.defaultValue,
                                onValueChange = { onDefaultChange(index, it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("默认选项") },
                                singleLine = true,
                            )
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onAdd) { Text("添加设置项") }
    }
}

@Composable
private fun SettingTypeChip(
    label: String,
    value: PluginBuilderSettingType,
    selected: PluginBuilderSettingType,
    onSelect: (PluginBuilderSettingType) -> Unit,
) {
    FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
}

@Composable
private fun ValidationSection(validation: PluginBuilderValidationDto?, validating: Boolean) {
    SectionCard("5. 实时检查与权限", "最终结果由 App 内同一套声明式运行时校验。") {
        if (validating) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                Text("正在检查…")
            }
        }
        if (validation == null) {
            Text("等待检查结果。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        Text(
            if (validation.valid) "可以安装和导出" else "还需要修正 ${validation.issues.count { it.severity == "error" }} 个问题",
            color = if (validation.valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
        )
        validation.issues.forEach { issue ->
            Text(
                "• ${issue.message}",
                color = if (issue.severity == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (validation.permissions.isNotEmpty()) {
            Text("自动申请的权限", style = MaterialTheme.typography.labelLarge)
            validation.permissions.forEach { permission -> PermissionRow(permission) }
        }
        if (validation.filename.isNotBlank()) {
            Text("导出文件：${validation.filename}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PermissionRow(permission: PluginBuilderPermissionDto) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(permission.title, fontWeight = FontWeight.Medium)
        Text(permission.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(permission.permission, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun StatusCard(message: String, error: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Text(
            message,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun ManifestDialog(json: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成的 plugin.json") },
        text = {
            SelectionContainer {
                Text(
                    json,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

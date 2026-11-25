package top.wkbin.zaomeng.feature.pluginbuilder

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.Chip
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Create
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.Tasks
import io.github.suqi8.coui.kmp.theme.COUITheme
import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.zaomeng.data.api.PluginBuilderActionMode
import top.wkbin.zaomeng.data.api.PluginBuilderPermissionDto
import top.wkbin.zaomeng.data.api.PluginBuilderSettingDraft
import top.wkbin.zaomeng.data.api.PluginBuilderSettingType
import top.wkbin.zaomeng.data.api.PluginBuilderTemplate
import top.wkbin.zaomeng.data.api.PluginBuilderValidationDto
import top.wkbin.zaomeng.data.api.PluginRuleActionType
import top.wkbin.zaomeng.data.api.PluginRuleDraft
import top.wkbin.zaomeng.data.api.PluginRuleEvent
import top.wkbin.zaomeng.platform.rememberFileExporter
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiPageScaffold
import top.wkbin.zaomeng.ui.coui.CouiTextField
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.couiPageScroll
import top.wkbin.zaomeng.ui.coui.isWideWindow

@Composable
fun PluginBuilderScreen(
    viewModel: PluginBuilderViewModel = koinViewModel(),
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showManifest by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    val fileExporter = rememberFileExporter(
        onSave = viewModel::savePendingExport,
        onCancelled = viewModel::cancelExportDestination,
    )

    LaunchedEffect(state.exportRequestId, state.exportDestinationPending) {
        if (!state.exportDestinationPending || state.pendingExportFilename.isBlank()) return@LaunchedEffect
        viewModel.consumeExportDestinationRequest(state.exportRequestId)
        fileExporter(state.pendingExportFilename, "application/zip")
    }

    CouiPageScaffold(
        title = "插件工坊",
        onBack = onBack,
        topBarStyle = CouiTopBarStyle.Small,
        wide = isWideWindow(),
    ) { scope ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .couiPageScroll(scope)
                .padding(scope.contentPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 920.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AiDraftCard(
                    idea = state.idea,
                    generating = state.generating,
                    onIdeaChange = viewModel::updateIdea,
                    onGenerate = viewModel::generateFromIdea,
                )
                BuilderIntroCard()

                SectionCard("完善插件资料", "AI 生成后仍可自由修改；插件 ID 会根据名称自动生成。") {
                    BuilderField(
                        value = state.draft.name,
                        onValueChange = viewModel::updateName,
                        label = "插件名称",
                        hint = "例如：温柔接话",
                        singleLine = true,
                    )
                    BuilderField(
                        value = state.draft.id,
                        onValueChange = {},
                        label = "自动生成的插件 ID",
                        hint = "用于安装和升级；相同 ID 会被识别为同一个插件。",
                        readOnly = true,
                        singleLine = true,
                    )
                    BuilderField(
                        value = state.draft.version,
                        onValueChange = viewModel::updateVersion,
                        label = "版本",
                        hint = "0.1.0",
                        singleLine = true,
                    )
                    BuilderField(
                        value = state.draft.description,
                        onValueChange = viewModel::updateDescription,
                        label = "一句话介绍（可选）",
                        singleLine = false,
                        minLines = 2,
                    )
                }

                SectionCard("调整玩法细节", "用自然语言继续打磨玩法；变量标签可把用户草稿或设置值带进提示词。") {
                    BuilderField(
                        value = state.draft.title,
                        onValueChange = viewModel::updateTitle,
                        label = "在聊天中显示的名称",
                        singleLine = true,
                    )
                    BuilderField(
                        value = state.draft.prompt,
                        onValueChange = viewModel::updatePrompt,
                        label = "自然语言提示词",
                        singleLine = false,
                        minLines = 6,
                        maxLines = 12,
                    )
                    Text("点击插入变量", style = COUITheme.textStyles.buttonM)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.draft.template == PluginBuilderTemplate.ChatAction) {
                            Chip(
                                selected = false,
                                onClick = { viewModel.insertVariable("{{seed_text}}") },
                                label = "用户草稿 {{seed_text}}",
                            )
                        }
                        state.draft.settings.forEach { setting ->
                            Chip(
                                selected = false,
                                onClick = { viewModel.insertVariable("{{config.${setting.key}}}") },
                                label = "${setting.title} {{config.${setting.key}}}",
                            )
                        }
                    }
                }

                AdvancedRuntimeSection(
                    expanded = showAdvanced,
                    template = state.draft.template,
                    actionMode = state.draft.actionMode,
                    onToggle = { showAdvanced = !showAdvanced },
                    onTemplateChange = viewModel::updateTemplate,
                    onActionModeChange = viewModel::updateActionMode,
                )

                if (state.draft.rules.isNotEmpty()) {
                    RuleChainSection(state.draft.rules)
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
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        if (state.working) {
                            CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("安装到本机试用")
                    }
                    Button(
                        onClick = viewModel::prepareExport,
                        enabled = state.validation?.valid == true && !state.validating && !state.working,
                    ) { Text("导出分享") }
                    TextButton(
                        text = "查看 plugin.json",
                        onClick = { showManifest = true },
                        enabled = state.validation?.manifestJson?.isNotBlank() == true,
                    )
                }
                Text(
                    "安装或导出的插件只包含声明式 JSON 和说明文件，不会携带或执行 Python、JAR、JavaScript 等任意代码。",
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
        }

        if (showManifest) {
            ManifestDialog(
                json = state.validation?.manifestJson.orEmpty(),
                onDismiss = { showManifest = false },
            )
        }
        state.generatedProposal?.let { proposal ->
            GeneratedDraftDialog(
                proposal = proposal,
                onApply = viewModel::applyGeneratedProposal,
                onDismiss = viewModel::dismissGeneratedProposal,
            )
        }
    }
}

@Composable
private fun AiDraftCard(
    idea: String,
    generating: Boolean,
    onIdeaChange: (String) -> Unit,
    onGenerate: () -> Unit,
) {
    Card(
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = COUITheme.colorScheme.containerThemeHalftone),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(COUIIcons.Create, contentDescription = null)
                Column {
                    Text("一句话生成插件", style = COUITheme.textStyles.headlineXS, fontWeight = FontWeight.SemiBold)
                    Text("先说你想怎么玩，AI 会自动设计实现方式、提示词和设置项。", style = COUITheme.textStyles.bodyXS)
                }
            }
            BuilderField(
                value = idea,
                onValueChange = onIdeaChange,
                label = "我想做一个……",
                hint = "例如：根据人物性格生成三条不同语气的回复供我选择",
                singleLine = false,
                minLines = 2,
                maxLines = 5,
                enabled = !generating,
            )
            Button(onClick = onGenerate, enabled = idea.isNotBlank() && !generating) {
                if (generating) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (generating) "正在设计插件" else "AI 帮我生成")
            }
        }
    }
}

@Composable
private fun GeneratedDraftDialog(
    proposal: PluginBuilderValidationDto,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = proposal.draft
    CouiAlertDialog(
        onDismissRequest = onDismiss,
        title = "确认使用 AI 草稿",
        confirmText = "填入工坊",
        onConfirm = onApply,
        confirmEnabled = proposal.valid,
        dismissText = "保留当前内容",
        onDismiss = onDismiss,
        body = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(draft.name, style = COUITheme.textStyles.headlineXS, fontWeight = FontWeight.SemiBold)
                Text(draft.description.ifBlank { "暂无简介" }, color = COUITheme.colorScheme.labelSecondary)
                Text("实现方式：${draft.template.displayName()}", fontWeight = FontWeight.Medium)
                if (draft.settings.isNotEmpty()) {
                    Text("设置：${draft.settings.joinToString("、") { it.title }}")
                }
                if (draft.rules.isNotEmpty()) {
                    Text("玩法规则：${draft.rules.joinToString("、") { it.title }}")
                }
                HorizontalDivider()
                Text("生成的提示词", style = COUITheme.textStyles.buttonM)
                Text(draft.prompt, style = COUITheme.textStyles.bodyXS)
                if (!proposal.valid) {
                    Text(
                        proposal.issues.joinToString("\n") { "• ${it.message}" },
                        color = COUITheme.colorScheme.error,
                    )
                }
                Text("确认后会替换当前表单内容，你仍可继续修改。", style = COUITheme.textStyles.bodyXS)
            }
        },
    )
}

@Composable
private fun RuleChainSection(rules: List<PluginRuleDraft>) {
    SectionCard("AI 玩法规则", "事件、条件和动作可以组合；运行状态只保存在当前会话。") {
        rules.forEachIndexed { index, rule ->
            Card(
                insideMargin = PaddingValues(0.dp),
                colors = CardDefaults.defaultColors(color = COUITheme.colorScheme.surfaceWithCard),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("${index + 1}. ${rule.title}", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${rule.event.displayName()} · ${rule.match.summary()}",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                    rule.actions.forEach { action ->
                        Text(
                            "→ ${when (action.type) {
                                PluginRuleActionType.AddInstruction -> "影响本轮生成：${action.instruction}"
                                PluginRuleActionType.SetState -> "记录状态 ${action.key} = ${action.value}"
                                PluginRuleActionType.IncrementState -> "状态 ${action.key} ${if (action.amount > 0) "+" else ""}${action.amount}"
                            }}",
                            style = COUITheme.textStyles.bodyXS,
                        )
                    }
                }
            }
        }
        Text(
            "当前版本由 AI 生成规则链；高级规则编辑器会作为后续功能加入。",
            style = COUITheme.textStyles.bodyXS,
            color = COUITheme.colorScheme.labelSecondary,
        )
    }
}

private fun PluginRuleEvent.displayName(): String = when (this) {
    PluginRuleEvent.BeforeGeneration -> "生成前"
    PluginRuleEvent.AfterTurn -> "回合结束后"
}

private fun top.wkbin.zaomeng.data.api.PluginRuleMatchDraft.summary(): String = buildList {
    if (keywords.isNotEmpty()) add("包含「${keywords.joinToString(" / ")}」")
    if (everyTurns > 0) add("每 $everyTurns 回合")
    if (chancePercent < 100) add("$chancePercent% 概率")
    if (stateKey.isNotBlank()) add("$stateKey = $stateEquals")
}.joinToString("，").ifBlank { "每次触发" }

private fun PluginBuilderTemplate.displayName(): String = when (this) {
    PluginBuilderTemplate.ChatAction -> "快捷动作"
    PluginBuilderTemplate.GenerationEnhancer -> "生成增强"
    PluginBuilderTemplate.TemporaryNpc -> "临时 NPC"
}

@Composable
private fun AdvancedRuntimeSection(
    expanded: Boolean,
    template: PluginBuilderTemplate,
    actionMode: PluginBuilderActionMode,
    onToggle: () -> Unit,
    onTemplateChange: (PluginBuilderTemplate) -> Unit,
    onActionModeChange: (PluginBuilderActionMode) -> Unit,
) {
    SectionCard("高级设置", "AI 已推断实现方式；通常无需调整，只有熟悉插件机制时再修改。") {
        Button(onClick = onToggle) {
            Text(if (expanded) "收起高级设置" else "查看高级设置")
        }
        if (expanded) {
            Text("触发方式", style = COUITheme.textStyles.buttonM)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TemplateChip("聊天按钮", PluginBuilderTemplate.ChatAction, template, onTemplateChange)
                TemplateChip("持续影响生成", PluginBuilderTemplate.GenerationEnhancer, template, onTemplateChange)
                TemplateChip("加入临时角色", PluginBuilderTemplate.TemporaryNpc, template, onTemplateChange)
            }
            if (template == PluginBuilderTemplate.ChatAction) {
                Text("结果数量", style = COUITheme.textStyles.buttonM)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(
                        selected = actionMode == PluginBuilderActionMode.Suggest,
                        onClick = { onActionModeChange(PluginBuilderActionMode.Suggest) },
                        label = "一个结果",
                    )
                    Chip(
                        selected = actionMode == PluginBuilderActionMode.Variants,
                        onClick = { onActionModeChange(PluginBuilderActionMode.Variants) },
                        label = "多个候选",
                    )
                }
            }
            Text(
                "这些选项只是宿主内部的安全执行方式，不是创作题材或玩法模板。",
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
    }
}

@Composable
private fun BuilderIntroCard() {
    Card(
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = COUITheme.colorScheme.containerThemeHalftone),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(COUIIcons.Tasks, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("先描述玩法，再决定实现", fontWeight = FontWeight.SemiBold)
                Text(
                    "不用先理解插件分类。AI 会把你的创意转换成安全的 Plugin API 2 草稿，并在安装前由真实运行时检查。",
                    style = COUITheme.textStyles.bodyXS,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = COUITheme.colorScheme.cardBackground),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = COUITheme.textStyles.headlineXS, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = COUITheme.textStyles.bodyXS, color = COUITheme.colorScheme.labelSecondary)
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
    Chip(selected = value == selected, onClick = { onSelect(value) }, label = label)
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
    SectionCard("可调设置（可选）", "添加后，用户可以在插件页调整这些值，并通过变量标签写入提示词。") {
        if (settings.isEmpty()) {
            Text("当前没有设置项。简单插件可以直接跳过。", color = COUITheme.colorScheme.labelSecondary)
        }
        settings.forEachIndexed { index, setting ->
            Card(
                insideMargin = PaddingValues(0.dp),
                colors = CardDefaults.defaultColors(color = COUITheme.colorScheme.surfaceWithCard),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("设置 ${index + 1}", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { onRemove(index) }) {
                            Icon(COUIIcons.Delete, contentDescription = "删除设置")
                        }
                    }
                    BuilderField(
                        value = setting.title,
                        onValueChange = { onTitleChange(index, it) },
                        label = "设置名称",
                        hint = "变量：{{config.${setting.key}}}",
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
                            Chip(
                                selected = setting.defaultValue == "false",
                                onClick = { onDefaultChange(index, "false") },
                                label = "默认关闭",
                            )
                            Chip(
                                selected = setting.defaultValue == "true",
                                onClick = { onDefaultChange(index, "true") },
                                label = "默认开启",
                            )
                        }
                        PluginBuilderSettingType.Integer -> BuilderField(
                            value = setting.defaultValue,
                            onValueChange = { onDefaultChange(index, it) },
                            label = "默认整数",
                            singleLine = true,
                        )
                        PluginBuilderSettingType.Enum -> {
                            var optionsText by remember(setting.key) {
                                mutableStateOf(setting.options.joinToString("，"))
                            }
                            BuilderField(
                                value = optionsText,
                                onValueChange = {
                                    optionsText = it
                                    onOptionsChange(index, it)
                                },
                                label = "候选项（逗号分隔）",
                                singleLine = true,
                            )
                            BuilderField(
                                value = setting.defaultValue,
                                onValueChange = { onDefaultChange(index, it) },
                                label = "默认选项",
                                singleLine = true,
                            )
                        }
                    }
                }
            }
        }
        Button(onClick = onAdd) { Text("添加设置项") }
    }
}

@Composable
private fun SettingTypeChip(
    label: String,
    value: PluginBuilderSettingType,
    selected: PluginBuilderSettingType,
    onSelect: (PluginBuilderSettingType) -> Unit,
) {
    Chip(selected = value == selected, onClick = { onSelect(value) }, label = label)
}

@Composable
private fun ValidationSection(validation: PluginBuilderValidationDto?, validating: Boolean) {
    SectionCard("实时检查与权限", "最终结果由 App 内同一套声明式运行时校验。") {
        if (validating) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                Text("正在检查…")
            }
        }
        if (validation == null) {
            Text("等待检查结果。", color = COUITheme.colorScheme.labelSecondary)
            return@SectionCard
        }
        Text(
            if (validation.valid) "可以安装和导出" else "还需要修正 ${validation.issues.count { it.severity == "error" }} 个问题",
            color = if (validation.valid) COUITheme.colorScheme.primary else COUITheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
        )
        validation.issues.forEach { issue ->
            Text(
                "• ${issue.message}",
                color = if (issue.severity == "error") COUITheme.colorScheme.error else COUITheme.colorScheme.labelSecondary,
                style = COUITheme.textStyles.bodyXS,
            )
        }
        if (validation.permissions.isNotEmpty()) {
            Text("自动申请的权限", style = COUITheme.textStyles.buttonM)
            validation.permissions.forEach { permission -> PermissionRow(permission) }
        }
        if (validation.filename.isNotBlank()) {
            Text("导出文件：${validation.filename}", style = COUITheme.textStyles.bodyXS)
        }
    }
}

@Composable
private fun PermissionRow(permission: PluginBuilderPermissionDto) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(permission.title, fontWeight = FontWeight.Medium)
        Text(permission.reason, style = COUITheme.textStyles.bodyXS, color = COUITheme.colorScheme.labelSecondary)
        Text(permission.permission, style = COUITheme.textStyles.description, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun StatusCard(message: String, error: Boolean) {
    Card(
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(
            color = if (error) COUITheme.colorScheme.errorTextBg else COUITheme.colorScheme.containerThemeHalftone,
        ),
    ) {
        Text(
            message,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            color = if (error) COUITheme.colorScheme.error else COUITheme.colorScheme.primaryText,
        )
    }
}

@Composable
private fun ManifestDialog(json: String, onDismiss: () -> Unit) {
    CouiAlertDialog(
        onDismissRequest = onDismiss,
        title = "生成的 plugin.json",
        confirmText = "关闭",
        onConfirm = onDismiss,
        dismissText = null,
        body = {
            SelectionContainer {
                Text(
                    json,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    style = COUITheme.textStyles.bodyXS,
                )
            }
        },
    )
}

@Composable
private fun BuilderField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CouiTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            label = label,
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
        )
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

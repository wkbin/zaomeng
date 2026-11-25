package top.wkbin.zaomeng.feature.persona

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.ProgressIndicatorDefaults
import io.github.suqi8.coui.kmp.basic.SnackbarHostState
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TagView
import io.github.suqi8.coui.kmp.basic.TagViewDefaults
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Create
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.ExpandLess
import io.github.suqi8.coui.kmp.icon.extended.ExpandMore
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.icon.extended.VolumeOff
import io.github.suqi8.coui.kmp.icon.extended.VolumeUp
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.wkbin.zaomeng.data.api.PersonaIssueDto
import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
import top.wkbin.zaomeng.data.api.PersonaRepairChangeDto
import top.wkbin.zaomeng.data.api.PersonaRepairProposalDto
import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.zaomeng.platform.PlatformTts
import top.wkbin.zaomeng.platform.rememberPlatformTts
import top.wkbin.zaomeng.ui.coui.CouiAlertDialog
import top.wkbin.zaomeng.ui.coui.CouiPageScaffold
import top.wkbin.zaomeng.ui.coui.CouiTopBarStyle
import top.wkbin.zaomeng.ui.coui.isWideWindow

@Composable
fun PersonaScreen(
    runId: String,
    character: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PersonaViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(runId, character) {
        viewModel.load(runId, character)
    }
    LaunchedEffect(state.notice?.id) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice.message)
        viewModel.dismissNotice(notice.id)
    }
    LaunchedEffect(state.deleted) {
        if (state.deleted) onDeleted()
    }

    PersonaContent(
        state = state,
        onBack = onBack,
        onDelete = viewModel::delete,
        onRetry = { viewModel.load(runId, character) },
        onFieldChange = viewModel::updateField,
        onReviewNoteChange = viewModel::updateReviewNote,
        onSuggestField = viewModel::suggestField,
        onApplyRepair = viewModel::applyRepairChange,
        onApplyAllRepairs = viewModel::applyAllRepairChanges,
        onRequestEvolution = { viewModel.requestEvolutionProposal() },
        onSave = viewModel::save,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )

    if (state.showEvolutionDialog && state.evolutionProposal != null) {
        PersonaEvolutionDialog(
            proposal = state.evolutionProposal!!,
            isApplying = state.isApplyingEvolution,
            onApply = viewModel::applyEvolution,
            onDismiss = viewModel::dismissEvolutionDialog,
        )
    }
}

@Composable
fun PersonaContent(
    state: PersonaUiState,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onFieldChange: (String, String) -> Unit,
    onReviewNoteChange: (String) -> Unit,
    onSuggestField: (String) -> Unit,
    onApplyRepair: (PersonaRepairChangeDto) -> Unit,
    onApplyAllRepairs: () -> Unit,
    onRequestEvolution: () -> Unit,
    onSave: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val expandedGroups = remember {
        mutableStateMapOf<String, Boolean>().apply {
            PERSONA_FIELD_GROUPS.forEach { group -> put(group.key, false) }
        }
    }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val tts = rememberPlatformTts()

    CouiPageScaffold(
        modifier = modifier.fillMaxSize(),
        title = state.character.ifBlank { "人物资料" },
        topBarStyle = CouiTopBarStyle.Small,
        contentTopSpacing = 8.dp,
        subtitle = if (state.hasUnsavedChanges) "有尚未保存的修改" else "",
        onBack = onBack,
        wide = isWideWindow(),
        snackbarHostState = snackbarHostState,
        actions = {
            if (!state.isSillyTavernCard) {
                IconButton(
                    onClick = onRequestEvolution,
                    enabled = state.hasLoaded && !state.isBusy,
                ) {
                    if (state.isGeneratingEvolution) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(COUIIcons.Create, contentDescription = "成长提炼")
                    }
                }
            }
            IconButton(onClick = onRetry, enabled = !state.isBusy) {
                Icon(COUIIcons.Refresh, contentDescription = "重新载入")
            }
            IconButton(
                onClick = { confirmDelete = true },
                enabled = state.hasLoaded && !state.isBusy,
            ) {
                Icon(COUIIcons.Delete, contentDescription = "删除人物")
            }
        },
        bottomBar = {
            if (state.hasLoaded) {
                SaveBar(
                    label = if (state.isSillyTavernCard) "保存角色卡" else "保存全部人物资料",
                    isSaving = state.isSaving,
                    enabled = !state.isLoading && state.suggestingField == null,
                    onSave = onSave,
                )
            }
        },
    ) { scope ->
        val contentModifier = Modifier.fillMaxSize().padding(scope.contentPadding)
        if (confirmDelete) {
            CouiAlertDialog(
                onDismissRequest = { if (!state.isDeleting) confirmDelete = false },
                title = "删除人物“${state.character}”？",
                confirmText = "删除",
                onConfirm = {
                    confirmDelete = false
                    onDelete()
                },
                confirmEnabled = !state.isDeleting,
                dismissText = "取消",
                onDismiss = { confirmDelete = false },
                body = {
                    Text(
                        "人物档案、头像和相关关系条目会永久删除；已有聊天记录会保留。此操作无法撤销。",
                        style = COUITheme.textStyles.bodyM,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                },
            )
        }
        when {
            state.isLoading && !state.hasLoaded -> PersonaLoading(
                modifier = contentModifier,
            )

            state.loadError.isNotBlank() && !state.hasLoaded -> PersonaLoadError(
                message = state.loadError,
                onRetry = onRetry,
                modifier = contentModifier,
            )

            state.isSillyTavernCard -> SillyTavernCardEditor(
                state = state,
                onFieldChange = onFieldChange,
                modifier = contentModifier,
            )

            else -> PersonaEditor(
                state = state,
                expandedGroups = expandedGroups,
                onToggleGroup = { key -> expandedGroups[key] = expandedGroups[key] != true },
                onFieldChange = onFieldChange,
                onReviewNoteChange = onReviewNoteChange,
                onSuggestField = onSuggestField,
                onApplyRepair = onApplyRepair,
                onApplyAllRepairs = onApplyAllRepairs,
                tts = tts,
                modifier = contentModifier,
            )
        }
    }
}

@Composable
private fun SillyTavernCardEditor(
    state: PersonaUiState,
    onFieldChange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(SILLYTAVERN_CARD_FIELDS, key = { it.key }) { field ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(field.label, style = COUITheme.textStyles.bodyXS)
                TextField(
                    value = state.fields[field.key].orEmpty(),
                    onValueChange = { onFieldChange(field.key, it) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = field.label },
                    label = "",
                    backgroundMode = TextFieldMode.Rectangle,
                    minLines = if (field.key == "description") 5 else 3,
                    maxLines = 12,
                    enabled = !state.isSaving,
                )
            }
        }
    }
}

@Composable
private fun PersonaEditor(
    state: PersonaUiState,
    expandedGroups: Map<String, Boolean>,
    onToggleGroup: (String) -> Unit,
    onFieldChange: (String, String) -> Unit,
    onReviewNoteChange: (String) -> Unit,
    onSuggestField: (String) -> Unit,
    onApplyRepair: (PersonaRepairChangeDto) -> Unit,
    onApplyAllRepairs: () -> Unit,
    tts: PlatformTts,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "source") {
            SourceCard(state)
        }
        item(key = "quality") {
            QualityCard(report = state.quality, error = state.qualityError)
        }
        item(key = "repair-proposal") {
            RepairProposalCard(
                proposal = state.repairProposal,
                error = state.repairError,
                appliedFields = state.appliedRepairFields,
                enabled = !state.isBusy,
                onApply = onApplyRepair,
                onApplyAll = onApplyAllRepairs,
            )
        }

        PERSONA_FIELD_GROUPS.forEach { group ->
            item(key = "group-${group.key}") {
                FieldGroupHeader(
                    group = group,
                    expanded = expandedGroups[group.key] == true,
                    completedCount = group.fields.count { state.fields[it.key].orEmpty().isNotBlank() },
                    onClick = { onToggleGroup(group.key) },
                )
            }
            if (expandedGroups[group.key] == true) {
                if (group.key == "voice") {
                    item(key = "voice-preview-card") {
                        VoicePreviewCard(
                            character = state.character,
                            fields = state.fields,
                            tts = tts,
                        )
                    }
                }
                items(group.fields, key = { "field-${it.key}" }) { field ->
                    PersonaFieldEditor(
                        spec = field,
                        value = state.fields[field.key].orEmpty(),
                        feedback = state.fieldFeedback[field.key],
                        qualityIssue = state.issueFor(field.key),
                        suggesting = state.suggestingField == field.key,
                        enabled = !state.isLoading && !state.isSaving,
                        suggestionsEnabled = state.suggestingField == null && !state.isSaving,
                        onValueChange = { onFieldChange(field.key, it) },
                        onSuggest = { onSuggestField(field.key) },
                    )
                }
            }
        }

        item(key = "review-note") {
            ReviewNoteCard(
                value = state.reviewNote,
                enabled = !state.isSaving,
                onValueChange = onReviewNoteChange,
            )
        }
        item(key = "footer-space") {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SourceCard(state: PersonaUiState) {
    val hasEditableProfile = state.editableProfilePath.isNotBlank()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = COUITheme.colorScheme.backgroundWithCard,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (hasEditableProfile) "当前使用校对稿" else "当前使用蒸馏稿",
                style = COUITheme.textStyles.headlineXS,
                fontWeight = FontWeight.SemiBold,
                color = COUITheme.colorScheme.labelPrimary,
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = if (hasEditableProfile) {
                    "这份资料已经保存过人工修改，仍可继续逐项复核。"
                } else {
                    "这是自动蒸馏生成的初稿。确认关键字段后，保存会生成可编辑校对稿。"
                },
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
    }
}

@Composable
private fun QualityCard(report: PersonaQualityReportDto?, error: String) {
    var showAllIssues by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = COUITheme.colorScheme.cardBackground,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "资料质量",
                style = COUITheme.textStyles.headlineXS,
                fontWeight = FontWeight.SemiBold,
                color = COUITheme.colorScheme.labelPrimary,
            )
            when {
                report != null -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = report.score.toString(),
                                    style = COUITheme.textStyles.displayS,
                                    fontWeight = FontWeight.Bold,
                                    color = qualityColor(report.score),
                                )
                                Text(
                                    text = " / ${report.maxScore}",
                                    modifier = Modifier.padding(bottom = 5.dp),
                                    style = COUITheme.textStyles.bodyM,
                                    color = COUITheme.colorScheme.labelSecondary,
                                )
                            }
                            Text(
                                text = qualityGradeLabel(report.grade),
                                style = COUITheme.textStyles.buttonM,
                                color = qualityColor(report.score),
                            )
                        }
                        TagView(
                            text = "${report.issues.size} 项待复核",
                            colors = TagViewDefaults.tagViewColors(
                                containerColor = COUITheme.colorScheme.secondaryContainer,
                                contentColor = COUITheme.colorScheme.labelPrimary,
                            ),
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = (report.score.toFloat() / report.maxScore.coerceAtLeast(1)).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(
                            foregroundColor = qualityColor(report.score),
                        ),
                    )
                    if (report.verdict.isNotBlank()) {
                        Spacer(modifier = Modifier.height(9.dp))
                        Text(
                            text = report.verdict,
                            style = COUITheme.textStyles.bodyM,
                            color = COUITheme.colorScheme.labelSecondary,
                        )
                    }
                    if (report.evidenceCoverage > 0 || report.confidence > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "问题字段证据覆盖 ${report.evidenceCoverage}% · 修复建议可信度 ${report.confidence}%",
                            style = COUITheme.textStyles.description,
                            color = COUITheme.colorScheme.labelSecondary,
                        )
                    }
                    if (report.issues.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        val visibleIssues = if (showAllIssues) report.issues else report.issues.take(4)
                        visibleIssues.forEach { issue -> QualityIssueRow(issue) }
                        if (report.issues.size > 4) {
                            TextButton(
                                text = if (showAllIssues) "收起问题" else "查看全部 ${report.issues.size} 项",
                                onClick = { showAllIssues = !showAllIssues },
                            )
                        }
                    }
                }

                error.isNotBlank() -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = error,
                        style = COUITheme.textStyles.bodyM,
                        color = COUITheme.colorScheme.error,
                    )
                }

                else -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = "正在生成质量报告…",
                            modifier = Modifier.padding(start = 10.dp),
                            style = COUITheme.textStyles.bodyM,
                            color = COUITheme.colorScheme.labelSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RepairProposalCard(
    proposal: PersonaRepairProposalDto?,
    error: String,
    appliedFields: Set<String>,
    enabled: Boolean,
    onApply: (PersonaRepairChangeDto) -> Unit,
    onApplyAll: () -> Unit,
) {
    if (proposal == null && error.isBlank()) return
    if (proposal?.status in setOf("not_available", "not_needed", "applied") && error.isBlank()) return
    var expanded by rememberSaveable(proposal?.createdAt, error) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = COUITheme.colorScheme.containerThemeHalftone,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "自动修复建议",
                        style = COUITheme.textStyles.headlineXS,
                        fontWeight = FontWeight.SemiBold,
                        color = COUITheme.colorScheme.labelPrimary,
                    )
                    proposal?.let {
                        Text(
                            "原文证据覆盖 ${it.evidenceCoverage}% · 平均可信度 ${it.confidence}%",
                            style = COUITheme.textStyles.description,
                            color = COUITheme.colorScheme.labelSecondary,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (expanded && !proposal?.changes.isNullOrEmpty()) {
                        TextButton(
                            text = "全部应用",
                            onClick = onApplyAll,
                            enabled = enabled && proposal.changes.any { it.field !in appliedFields },
                        )
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) COUIIcons.ExpandLess else COUIIcons.ExpandMore,
                            contentDescription = if (expanded) "收起自动修复建议" else "展开自动修复建议",
                        )
                    }
                }
            }
            if (expanded) {
                if (error.isNotBlank()) {
                    Text(error, color = COUITheme.colorScheme.error)
                } else if (proposal != null && proposal.changes.isEmpty()) {
                    Text(
                        "检测到 ${proposal.issues.size} 项待完善内容，未生成可安全应用的修改；未知字段将保持为空。",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                } else proposal?.changes.orEmpty().forEach { change ->
                    val applied = change.field in appliedFields
                    Card(
                        colors = CardDefaults.defaultColors(
                            color = COUITheme.colorScheme.cardBackground,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Text(
                                personaFieldLabel(change.field),
                                style = COUITheme.textStyles.headlineXS,
                                fontWeight = FontWeight.SemiBold,
                                color = COUITheme.colorScheme.labelPrimary,
                            )
                            Text(
                                "修改前：${change.before.ifBlank { "（空）" }}",
                                style = COUITheme.textStyles.bodyXS,
                                color = COUITheme.colorScheme.labelSecondary,
                            )
                            Text(
                                "修改后：${change.after}",
                                style = COUITheme.textStyles.bodyM,
                                color = COUITheme.colorScheme.labelPrimary,
                            )
                            if (change.reason.isNotBlank()) {
                                Text(
                                    "原因：${change.reason} · 可信度 ${change.confidence}%",
                                    style = COUITheme.textStyles.description,
                                    color = COUITheme.colorScheme.labelSecondary,
                                )
                            }
                            change.evidence.firstOrNull()?.let { evidence ->
                                Text(
                                    "证据：${evidence.title}（字符 ${evidence.startChar}–${evidence.endChar}）",
                                    style = COUITheme.textStyles.description,
                                    color = COUITheme.colorScheme.primary,
                                )
                                Text(
                                    evidence.excerpt,
                                    style = COUITheme.textStyles.bodyXS,
                                    color = COUITheme.colorScheme.labelSecondary,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Button(
                                onClick = { onApply(change) },
                                enabled = enabled && !applied,
                                modifier = Modifier.align(Alignment.End),
                                colors = ButtonDefaults.buttonColors(),
                            ) {
                                Text(if (applied) "已应用" else "应用到草稿")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun personaFieldLabel(field: String): String = PERSONA_FIELD_GROUPS.asSequence()
    .flatMap { it.fields.asSequence() }
    .firstOrNull { it.key == field }
    ?.label
    ?: field

@Composable
private fun QualityIssueRow(issue: PersonaIssueDto) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val severity = severityColor(issue.severity)
            TagView(
                text = severityLabel(issue.severity),
                colors = TagViewDefaults.tagViewColors(
                    containerColor = severity.copy(alpha = 0.14f),
                    contentColor = severity,
                ),
            )
            if (issue.fields.isNotEmpty()) {
                Text(
                    text = issue.fields.map(::fieldLabel).joinToString("、"),
                    modifier = Modifier.padding(start = 8.dp),
                    style = COUITheme.textStyles.description,
                    color = COUITheme.colorScheme.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (issue.message.isNotBlank()) {
            Text(
                text = issue.message,
                modifier = Modifier.padding(top = 5.dp),
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.labelPrimary,
            )
        }
        if (issue.suggestion.isNotBlank()) {
            Text(
                text = issue.suggestion,
                modifier = Modifier.padding(top = 3.dp),
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
    }
}

@Composable
private fun FieldGroupHeader(
    group: PersonaFieldGroup,
    expanded: Boolean,
    completedCount: Int,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = if (expanded) {
                COUITheme.colorScheme.secondaryContainer
            } else {
                COUITheme.colorScheme.backgroundWithCard
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.title,
                        style = COUITheme.textStyles.headlineXS,
                        fontWeight = FontWeight.SemiBold,
                        color = COUITheme.colorScheme.labelPrimary,
                    )
                    Text(
                        text = "$completedCount/${group.fields.size}",
                        modifier = Modifier.padding(start = 8.dp),
                        style = COUITheme.textStyles.description,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
                Text(
                    text = group.description,
                    modifier = Modifier.padding(top = 4.dp),
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = if (expanded) COUIIcons.ExpandLess else COUIIcons.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
            )
        }
    }
}

@Composable
private fun PersonaFieldEditor(
    spec: PersonaFieldSpec,
    value: String,
    feedback: PersonaFieldFeedback?,
    qualityIssue: PersonaIssueDto?,
    suggesting: Boolean,
    enabled: Boolean,
    suggestionsEnabled: Boolean,
    onValueChange: (String) -> Unit,
    onSuggest: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = COUITheme.colorScheme.cardBackground,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = spec.label,
                        style = COUITheme.textStyles.headlineXS,
                        fontWeight = FontWeight.SemiBold,
                        color = COUITheme.colorScheme.labelPrimary,
                    )
                    Text(
                        text = spec.key,
                        style = COUITheme.textStyles.description,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
                if (spec.supportsSuggestion) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (suggesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = COUIIcons.Create,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                                tint = COUITheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.size(6.dp))
                        TextButton(
                            text = if (suggesting) "生成中" else "AI 补全",
                            onClick = onSuggest,
                            enabled = suggestionsEnabled && !suggesting,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            val helper = feedback?.message?.takeIf(String::isNotBlank)
                ?: qualityIssue?.suggestion?.takeIf(String::isNotBlank)
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = "填写可直接约束人物表现的具体描述",
                backgroundMode = TextFieldMode.Rectangle,
                minLines = 2,
                maxLines = 8,
                isError = feedback?.kind == PersonaFeedbackKind.Error ||
                    qualityIssue?.severity == "high",
            )
            helper?.let {
                Text(
                    it,
                    style = COUITheme.textStyles.bodyXS,
                    color = when (feedback?.kind) {
                        PersonaFeedbackKind.Success -> COUITheme.colorScheme.primary
                        PersonaFeedbackKind.Error -> COUITheme.colorScheme.error
                        PersonaFeedbackKind.Loading -> COUITheme.colorScheme.primary
                        null -> COUITheme.colorScheme.labelSecondary
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ReviewNoteCard(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = COUITheme.colorScheme.backgroundWithCard,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "本次校对备注",
                style = COUITheme.textStyles.headlineXS,
                fontWeight = FontWeight.SemiBold,
                color = COUITheme.colorScheme.labelPrimary,
            )
            Text(
                text = "可记录改动依据、仍存疑的地方，备注会随保存事件写入书卷。",
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.labelSecondary,
            )
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = "例如：根据第三章对白补充口吻与底线",
                backgroundMode = TextFieldMode.Rectangle,
                minLines = 2,
                maxLines = 5,
            )
        }
    }
}

@Composable
private fun SaveBar(
    label: String,
    isSaving: Boolean,
    enabled: Boolean,
    onSave: () -> Unit,
) {
    Button(
        onClick = { if (!isSaving) onSave() },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                colors = ProgressIndicatorDefaults.loadingViewColors(
                    foregroundColor = COUITheme.colorScheme.onPrimary,
                ),
            )
        } else {
            Icon(
                COUIIcons.Ok,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = if (isSaving) "正在保存…" else label,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun PersonaLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = "正在载入人物资料…",
                modifier = Modifier.padding(top = 14.dp),
                style = COUITheme.textStyles.bodyL,
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
    }
}

@Composable
private fun PersonaLoadError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            colors = CardDefaults.defaultColors(
                color = COUITheme.colorScheme.containerRed,
            ),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "人物资料没有载入",
                    style = COUITheme.textStyles.headlineXS,
                    fontWeight = FontWeight.SemiBold,
                    color = COUITheme.colorScheme.error,
                )
                Text(
                    text = message,
                    modifier = Modifier.padding(top = 8.dp),
                    style = COUITheme.textStyles.bodyM,
                    color = COUITheme.colorScheme.labelPrimary,
                )
                Button(
                    onClick = onRetry,
                    modifier = Modifier.padding(top = 16.dp),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Icon(
                        COUIIcons.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("重新载入", modifier = Modifier.padding(start = 7.dp))
                }
            }
        }
    }
}

private fun fieldLabel(key: String): String = PERSONA_FIELD_GROUPS.asSequence()
    .flatMap { it.fields.asSequence() }
    .firstOrNull { it.key == key }
    ?.label
    ?: key

private fun qualityGradeLabel(grade: String): String = when (grade) {
    "ready" -> "可以进入对话回归"
    "usable" -> "基本可用"
    "needs_work" -> "仍需补强"
    "insufficient" -> "资料不足"
    else -> grade.ifBlank { "待评估" }
}

private fun severityLabel(severity: String): String = when (severity) {
    "high" -> "高优先级"
    "medium" -> "中优先级"
    "low" -> "低优先级"
    else -> "待复核"
}

@Composable
private fun qualityColor(score: Int): Color = when {
    score >= 80 -> COUITheme.colorScheme.primary
    score >= 60 -> COUITheme.colorScheme.tips
    else -> COUITheme.colorScheme.error
}

@Composable
private fun severityColor(severity: String): Color = when (severity) {
    "high" -> COUITheme.colorScheme.error
    "medium" -> COUITheme.colorScheme.tips
    else -> COUITheme.colorScheme.primary
}

@Composable
private fun VoicePreviewCard(
    character: String,
    fields: Map<String, String>,
    tts: PlatformTts,
) {
    val isSpeaking by tts.isSpeaking.collectAsStateWithLifecycle()
    val speakingId by tts.currentSpeakingId.collectAsStateWithLifecycle()
    val isThisSpeaking = isSpeaking && speakingId == "persona-voice-preview"

    val voiceName = fields["voice_name"].orEmpty()
    val pitch = fields["voice_pitch"]?.toFloatOrNull() ?: 1.0f
    val speed = fields["voice_speed"]?.toFloatOrNull() ?: 1.0f
    val testText = fields["typical_lines"]?.split("；", ";")?.firstOrNull()?.takeIf(String::isNotBlank)
        ?: "${character}在此。有何吩咐？"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.defaultColors(
            color = COUITheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "声线实时试听",
                    style = COUITheme.textStyles.headlineXS,
                    fontWeight = FontWeight.SemiBold,
                    color = COUITheme.colorScheme.labelPrimary,
                )
                Text(
                    text = "试听文案：“$testText”",
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Button(
                onClick = {
                    if (isThisSpeaking) {
                        tts.stop()
                    } else {
                        tts.speak(
                            id = "persona-voice-preview",
                            text = testText,
                            pitch = pitch,
                            speed = speed,
                            voiceName = voiceName,
                        )
                    }
                },
                modifier = Modifier.padding(start = 12.dp),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Icon(
                    if (isThisSpeaking) COUIIcons.VolumeOff else COUIIcons.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (isThisSpeaking) "停止" else "试听",
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import top.wkbin.zaomeng.platform.PlatformTts
import top.wkbin.zaomeng.platform.rememberPlatformTts
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.data.api.PersonaIssueDto
import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
import top.wkbin.zaomeng.data.api.PersonaRepairChangeDto
import top.wkbin.zaomeng.data.api.PersonaRepairProposalDto
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
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

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.character.ifBlank { "人物资料" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.hasUnsavedChanges) {
                            Text(
                                text = "有尚未保存的修改",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRequestEvolution,
                        enabled = state.hasLoaded && !state.isBusy,
                    ) {
                        if (state.isGeneratingEvolution) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = "成长提炼")
                        }
                    }
                    IconButton(onClick = onRetry, enabled = !state.isBusy) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "重新载入")
                    }
                    IconButton(
                        onClick = { confirmDelete = true },
                        enabled = state.hasLoaded && !state.isBusy,
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除人物")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            if (state.hasLoaded) {
                SaveBar(
                    isSaving = state.isSaving,
                    enabled = !state.isLoading && state.suggestingField == null,
                    onSave = onSave,
                )
            }
        },
    ) { innerPadding ->
        when {
            state.isLoading && !state.hasLoaded -> PersonaLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            state.loadError.isNotBlank() && !state.hasLoaded -> PersonaLoadError(
                message = state.loadError,
                onRetry = onRetry,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!state.isDeleting) confirmDelete = false },
            title = { Text("删除人物“${state.character}”？") },
            text = { Text("人物档案、头像和相关关系条目会永久删除；已有聊天记录会保留。此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    enabled = !state.isDeleting,
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, enabled = !state.isDeleting) {
                    Text("取消")
                }
            },
        )
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (hasEditableProfile) "当前使用校对稿" else "当前使用蒸馏稿",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = if (hasEditableProfile) {
                    "这份资料已经保存过人工修改，仍可继续逐项复核。"
                } else {
                    "这是自动蒸馏生成的初稿。确认关键字段后，保存会生成可编辑校对稿。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QualityCard(report: PersonaQualityReportDto?, error: String) {
    var showAllIssues by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "资料质量",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
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
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = qualityColor(report.score),
                                )
                                Text(
                                    text = " / ${report.maxScore}",
                                    modifier = Modifier.padding(bottom = 5.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = qualityGradeLabel(report.grade),
                                style = MaterialTheme.typography.labelLarge,
                                color = qualityColor(report.score),
                            )
                        }
                        Text(
                            text = "${report.issues.size} 项待复核",
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    RoundedCornerShape(999.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { (report.score.toFloat() / report.maxScore.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = qualityColor(report.score),
                    )
                    if (report.verdict.isNotBlank()) {
                        Spacer(modifier = Modifier.height(9.dp))
                        Text(
                            text = report.verdict,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (report.evidenceCoverage > 0 || report.confidence > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "问题字段证据覆盖 ${report.evidenceCoverage}% · 修复建议可信度 ${report.confidence}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (report.issues.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        val visibleIssues = if (showAllIssues) report.issues else report.issues.take(4)
                        visibleIssues.forEach { issue -> QualityIssueRow(issue) }
                        if (report.issues.size > 4) {
                            TextButton(onClick = { showAllIssues = !showAllIssues }) {
                                Text(if (showAllIssues) "收起问题" else "查看全部 ${report.issues.size} 项")
                            }
                        }
                    }
                }

                error.isNotBlank() -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = "正在生成质量报告…",
                            modifier = Modifier.padding(start = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
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
                    Text("自动修复建议", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    proposal?.let {
                        Text(
                            "原文证据覆盖 ${it.evidenceCoverage}% · 平均可信度 ${it.confidence}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (expanded && !proposal?.changes.isNullOrEmpty()) {
                        TextButton(
                            onClick = onApplyAll,
                            enabled = enabled && proposal.changes.any { it.field !in appliedFields },
                        ) { Text("全部应用") }
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (expanded) "收起自动修复建议" else "展开自动修复建议",
                        )
                    }
                }
            }
            if (expanded) {
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                } else if (proposal != null && proposal.changes.isEmpty()) {
                    Text(
                        "检测到 ${proposal.issues.size} 项待完善内容，未生成可安全应用的修改；未知字段将保持为空。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else proposal?.changes.orEmpty().forEach { change ->
                    val applied = change.field in appliedFields
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Text(personaFieldLabel(change.field), fontWeight = FontWeight.SemiBold)
                            Text(
                                "修改前：${change.before.ifBlank { "（空）" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("修改后：${change.after}", style = MaterialTheme.typography.bodyMedium)
                            if (change.reason.isNotBlank()) {
                                Text(
                                    "原因：${change.reason} · 可信度 ${change.confidence}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            change.evidence.firstOrNull()?.let { evidence ->
                                Text(
                                    "证据：${evidence.title}（字符 ${evidence.startChar}–${evidence.endChar}）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    evidence.excerpt,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            OutlinedButton(
                                onClick = { onApply(change) },
                                enabled = enabled && !applied,
                                modifier = Modifier.align(Alignment.End),
                            ) { Text(if (applied) "已应用" else "应用到草稿") }
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
            Text(
                text = severityLabel(issue.severity),
                modifier = Modifier
                    .background(severityColor(issue.severity).copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = severityColor(issue.severity),
            )
            if (issue.fields.isNotEmpty()) {
                Text(
                    text = issue.fields.map(::fieldLabel).joinToString("、"),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (issue.message.isNotBlank()) {
            Text(
                text = issue.message,
                modifier = Modifier.padding(top = 5.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (issue.suggestion.isNotBlank()) {
            Text(
                text = issue.suggestion,
                modifier = Modifier.padding(top = 3.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
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
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "$completedCount/${group.fields.size}",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = group.description,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
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
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = spec.key,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (spec.supportsSuggestion) {
                    TextButton(
                        onClick = onSuggest,
                        enabled = suggestionsEnabled && !suggesting,
                    ) {
                        if (suggesting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                        Text(
                            text = if (suggesting) "生成中" else "AI 补全",
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            val helper = feedback?.message?.takeIf(String::isNotBlank)
                ?: qualityIssue?.suggestion?.takeIf(String::isNotBlank)
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                minLines = 2,
                maxLines = 8,
                placeholder = { Text("填写可直接约束人物表现的具体描述") },
                isError = feedback?.kind == PersonaFeedbackKind.Error || qualityIssue?.severity == "high",
                supportingText = {
                    helper?.let {
                        Text(
                            it,
                            color = when (feedback?.kind) {
                                PersonaFeedbackKind.Success -> MaterialTheme.colorScheme.primary
                                PersonaFeedbackKind.Error -> MaterialTheme.colorScheme.error
                                PersonaFeedbackKind.Loading -> MaterialTheme.colorScheme.primary
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "本次校对备注",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "可记录改动依据、仍存疑的地方，备注会随保存事件写入书卷。",
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                minLines = 2,
                maxLines = 5,
                placeholder = { Text("例如：根据第三章对白补充口吻与底线") },
            )
        }
    }
}

@Composable
private fun SaveBar(
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
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text(
            text = if (isSaving) "正在保存…" else "保存全部人物资料",
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
                style = MaterialTheme.typography.bodyLarge,
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "人物资料没有载入",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = message,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
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
    score >= 80 -> MaterialTheme.colorScheme.primary
    score >= 60 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

@Composable
private fun severityColor(severity: String): Color = when (severity) {
    "high" -> MaterialTheme.colorScheme.error
    "medium" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
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
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "试听文案：“$testText”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            OutlinedButton(
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
            ) {
                Icon(
                    if (isThisSpeaking) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
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

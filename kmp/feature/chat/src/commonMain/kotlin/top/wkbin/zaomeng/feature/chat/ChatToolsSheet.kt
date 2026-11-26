package top.wkbin.zaomeng.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Create
import io.github.suqi8.coui.kmp.icon.extended.Favorites
import io.github.suqi8.coui.kmp.icon.extended.HorizontalSplit
import io.github.suqi8.coui.kmp.icon.extended.Recent
import io.github.suqi8.coui.kmp.icon.extended.Tasks
import io.github.suqi8.coui.kmp.overlay.OverlayBottomSheet
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.serialization.json.JsonObject
import top.wkbin.zaomeng.data.api.AuthorNoteDto
import top.wkbin.zaomeng.data.api.DialogueMemoryDto
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.feature.chat.insights.*

@Composable
fun ChatToolsSheet(
    state: ChatUiState,
    readingLayout: Boolean,
    onToggleReadingLayout: () -> Unit,
    onTogglePromptCapture: () -> Unit,
    onDismiss: () -> Unit,
    onDirector: (String, String) -> Unit,
    onCorrectLatest: () -> Unit,
    onDeepReviewLatest: () -> Unit,
    onBranchTurn: (String) -> Unit,
    onBranchScene: (Int) -> Unit,
    onUpdateBranchMeta: (String, Boolean) -> Unit,
    onUpdateAuthorNote: (AuthorNoteDto) -> Unit,
    onToggleMainlineEvent: (String, Boolean) -> Unit,
    onOpenExistingBranch: (String) -> Unit,
    onLoadScenes: () -> Unit,
    onRecommendScene: () -> Unit,
    onSwitchScene: (String, String, Boolean) -> Unit,
    onSaveMemory: (DialogueMemoryDto) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onUpdateAutomaticMemoryStatus: (String, String) -> Unit,
    onMergeDuplicateMemories: () -> Unit,
    onRelationLock: (String, Boolean) -> Unit,
    onOpenStoryRecap: () -> Unit,
) {
    var directorDialog by remember { mutableStateOf(false) }
    var directorGoal by remember { mutableStateOf("") }
    var directorAction by remember { mutableStateOf("advance") }
    var memoryDraft by remember { mutableStateOf<DialogueMemoryDto?>(null) }
    var memorySaveBaseline by remember { mutableStateOf<Long?>(null) }
    var pendingMemoryDeletion by remember { mutableStateOf<DialogueMemoryDto?>(null) }
    var pendingRelationLockChange by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var relationLockHelpDialog by remember { mutableStateOf(false) }
    var branchMetaDialog by remember { mutableStateOf(false) }
    var authorNoteDialog by remember(state.sessionId) { mutableStateOf(false) }
    var showPromptPreview by remember(state.sessionId) { mutableStateOf(false) }
    var pendingAuthorNote by remember(state.sessionId) { mutableStateOf<AuthorNoteDto?>(null) }
    var branchLabel by remember(state.session?.sessionId) {
        mutableStateOf(state.session?.branchMeta?.stringValue("label").orEmpty())
    }
    var mainline by remember(state.session?.sessionId) {
        mutableStateOf(state.session?.branchMeta?.booleanValue("is_mainline") == true)
    }
    val toolsEnabled = state.canUseTools
    val session = state.session
    val preview = state.promptPreview
    if (showPromptPreview && preview != null) {
        DialoguePromptPreviewDialog(preview, onDismiss = { showPromptPreview = false })
    }
    LaunchedEffect(session?.authorNote, pendingAuthorNote, state.toolBusy) {
        if (pendingAuthorNote != null && session?.authorNote == pendingAuthorNote && state.toolBusy.isBlank()) {
            authorNoteDialog = false
            pendingAuthorNote = null
        }
    }
    if (authorNoteDialog && session != null) {
        AuthorNoteDialog(
            current = session.authorNote,
            saving = state.toolBusy == "author_note",
            error = state.error.takeIf { pendingAuthorNote != null }.orEmpty(),
            onSave = {
                pendingAuthorNote = it
                onUpdateAuthorNote(it)
            },
            onDismiss = {
                authorNoteDialog = false
                pendingAuthorNote = null
            },
        )
    }
    val branchNodes = remember(session) { session?.branchGraph?.branchNodeInsights().orEmpty() }
    val consistency = remember(session) { session?.consistencyMonitor?.consistencyInsight() }
    val characterArcs = remember(session) { session?.characterArcs.orEmpty().characterArcInsights() }
    val speakerState = remember(session) { session?.let { speakerInsights(it.speakerActivity, it.speakerBalance) } }
    val relationTimelines = remember(session) { session?.relationTimeline.orEmpty().relationTimelineInsights() }
    val eventSignals = remember(session) { session?.eventSignals?.eventSignalInsights().orEmpty() }
    val generationStats = remember(session) { session?.generationCacheStats?.generationInsight() }
    val contextUsage = remember(session) { session?.latestContextUsage?.contextUsageInsight() }

    LaunchedEffect(state.memorySaveRevision) {
        val baseline = memorySaveBaseline
        if (baseline != null && state.memorySaveRevision > baseline) {
            memoryDraft = null
            memorySaveBaseline = null
        }
    }

    DirectorToolDialog(
        visible = directorDialog,
        goal = directorGoal,
        action = directorAction,
        enabled = toolsEnabled,
        onGoalChange = { directorGoal = it },
        onActionChange = { directorAction = it },
        onConfirm = {
            directorDialog = false
            onDirector(directorGoal, directorAction)
        },
        onDismiss = { directorDialog = false },
    )
    RelationLockHelpDialog(
        visible = relationLockHelpDialog,
        onDismiss = { relationLockHelpDialog = false },
    )

    memoryDraft?.let { memory ->
        MemoryEditor(
            memory = memory,
            busy = state.toolBusy == "memory",
            enabled = toolsEnabled,
            onChange = { memoryDraft = it },
            onSave = {
                memorySaveBaseline = state.memorySaveRevision
                onSaveMemory(it)
            },
            onDismiss = {
                memoryDraft = null
                memorySaveBaseline = null
            },
        )
    }

    MemoryDeletionDialog(
        memory = pendingMemoryDeletion,
        onConfirm = { memoryId ->
            pendingMemoryDeletion = null
            onDeleteMemory(memoryId)
        },
        onDismiss = { pendingMemoryDeletion = null },
    )
    RelationLockConfirmationDialog(
        change = pendingRelationLockChange,
        onConfirm = { pairKey, shouldLock ->
            pendingRelationLockChange = null
            onRelationLock(pairKey, shouldLock)
        },
        onDismiss = { pendingRelationLockChange = null },
    )

    BranchMetaDialog(
        visible = branchMetaDialog,
        label = branchLabel,
        mainline = mainline,
        enabled = toolsEnabled,
        onLabelChange = { branchLabel = it },
        onMainlineChange = { mainline = it },
        onConfirm = {
            branchMetaDialog = false
            onUpdateBranchMeta(branchLabel, mainline)
        },
        onDismiss = { branchMetaDialog = false },
    )

    OverlayBottomSheet(
        show = true,
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 4.dp,
                end = 16.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("会话工具", style = COUITheme.textStyles.headlineM, fontWeight = FontWeight.SemiBold)
                Text(
                    "当前分支 ${state.sessionId}",
                    style = COUITheme.textStyles.bodyXS,
                    color = COUITheme.colorScheme.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            item {
                TextButton(
                    text = if (readingLayout) "切换到气泡排版" else "切换到阅读排版",
                    onClick = onToggleReadingLayout,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                ToolSection("剧情复盘") {
                    ToolButton(
                        label = "查看剧情卡并分享",
                        icon = { Icon(COUIIcons.Recent, contentDescription = null) },
                        enabled = toolsEnabled,
                        onClick = onOpenStoryRecap,
                    )
                }
            }

            if (session != null) {
                item {
                    TextButton(
                        text = "作者注释",
                        onClick = { authorNoteDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = toolsEnabled,
                    )
                }
                item {
                    ToolSection("模型提示词") {
                        TextButton(
                            text = if (state.captureNextPrompt) "取消捕获下一轮" else "捕获下一轮提示词",
                            onClick = onTogglePromptCapture,
                            enabled = toolsEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (preview != null) {
                            TextButton(
                                text = "查看上次捕获",
                                onClick = { showPromptPreview = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            item {
                ToolSection("最新一轮") {
                    ToolButton(
                        label = "生成修正版",
                        icon = { Icon(COUIIcons.Create, contentDescription = null) },
                        busy = state.toolBusy == "correct",
                        enabled = toolsEnabled && state.session?.transcript?.isNotEmpty() == true,
                        onClick = onCorrectLatest,
                    )
                    ToolButton(
                        label = "深度复核",
                        icon = { Icon(COUIIcons.Tasks, contentDescription = null) },
                        busy = state.toolBusy == "review",
                        enabled = toolsEnabled && state.session?.transcript?.isNotEmpty() == true,
                        onClick = onDeepReviewLatest,
                    )
                }
            }

            item {
                ToolSection("分支") {
                    Button(
                        onClick = { branchMetaDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = toolsEnabled,
                    ) {
                        Icon(COUIIcons.HorizontalSplit, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("编辑当前分支")
                    }
                    state.session?.eventTimeline.orEmpty().takeLast(5).reversed().forEach { event ->
                        val turnId = event.stringValue("turn_id")
                        if (turnId.isNotBlank()) {
                            EventBranchRow(
                                title = event.firstString("summary", "message", "label", "event_type")
                                    .ifBlank { "从这一轮开始" },
                                detail = turnId,
                                locked = event.booleanValue("is_mainline_anchor"),
                                enabled = toolsEnabled,
                                onBranch = { onBranchTurn(turnId) },
                                onToggleLock = { locked -> onToggleMainlineEvent(turnId, locked) },
                            )
                        }
                    }
                    state.session?.sceneHistory.orEmpty().forEachIndexed { index, scene ->
                        BranchRow(
                            title = scene.firstString("title", "location").ifBlank { "场景 ${index + 1}" },
                            detail = "从场景 ${index + 1} 创建分支",
                            enabled = toolsEnabled,
                            onClick = { onBranchScene(index) },
                        )
                    }
                }
            }

            if (branchNodes.isNotEmpty()) {
                item {
                    BranchGraphInsights(
                        nodes = branchNodes,
                        enabled = toolsEnabled,
                        onOpenBranch = onOpenExistingBranch,
                    )
                }
            }

            item {
                ToolSection("场景") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            text = "载入场景卡",
                            onClick = onLoadScenes,
                            modifier = Modifier.weight(1f),
                            enabled = toolsEnabled,
                        )
                        Button(
                            onClick = onRecommendScene,
                            modifier = Modifier.weight(1f),
                            enabled = toolsEnabled,
                        ) {
                            if (state.toolBusy == "recommend_scene") {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                            Text("推荐下一幕", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    state.sceneCards.forEach { card ->
                        SceneRow(
                            card = card,
                            recommended = card.cardId == state.recommendedSceneCardId,
                            enabled = toolsEnabled,
                            transition = if (card.cardId == state.recommendedSceneCardId) {
                                state.recommendedTransition
                            } else {
                                ""
                            },
                            onSwitch = onSwitchScene,
                        )
                    }
                }
            }

            item {
                ToolSection("用户固定记忆") {
                    Button(
                        onClick = { memoryDraft = DialogueMemoryDto() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = toolsEnabled,
                    ) {
                        Icon(COUIIcons.Favorites, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("添加记忆")
                    }
                    state.session?.memoryLedger.orEmpty().forEach { memory ->
                        MemoryRow(
                            memory = memory,
                            enabled = toolsEnabled,
                            onEdit = { memoryDraft = memory },
                            onDelete = { pendingMemoryDeletion = memory },
                        )
                    }
                }
            }

            item {
                val quality = state.memoryQuality
                val automatic = quality.entries.filter { it.source == "automatic" }
                ToolSection("自动记忆质量") {
                    Text(
                        "启用 ${quality.activeCount} · 过期 ${quality.staleCount} · 冲突 ${quality.conflictCount}",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                    if (quality.duplicateGroups.isNotEmpty()) {
                        TextButton(
                            text = "合并 ${quality.duplicateGroups.size} 组重复记忆",
                            onClick = onMergeDuplicateMemories,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = toolsEnabled,
                        )
                    }
                    val currentHits = automatic.filter {
                        quality.latestHitTurnId.isNotBlank() && it.lastHitTurnId == quality.latestHitTurnId
                    }
                    if (currentHits.isNotEmpty()) {
                        Text("本轮命中", style = COUITheme.textStyles.buttonM)
                        currentHits.forEach { memory ->
                            AutomaticMemoryRow(memory, toolsEnabled, onUpdateAutomaticMemoryStatus)
                        }
                    }
                    val remaining = automatic.filterNot { it in currentHits }
                    if (remaining.isNotEmpty()) {
                        Text("全部自动记忆", style = COUITheme.textStyles.buttonM)
                        remaining.takeLast(40).asReversed().forEach { memory ->
                            AutomaticMemoryRow(memory, toolsEnabled, onUpdateAutomaticMemoryStatus)
                        }
                    } else if (automatic.isEmpty()) {
                        Text(
                            "完成对话后会自动建立带来源轮次的本地记忆。",
                            style = COUITheme.textStyles.bodyXS,
                            color = COUITheme.colorScheme.labelSecondary,
                        )
                    }
                }
            }

            state.session?.takeIf { it.relationMatrix.isNotEmpty() }?.let { session ->
                item {
                    RelationLockSection(
                        session = session,
                        enabled = toolsEnabled,
                        onShowHelp = { relationLockHelpDialog = true },
                        onRequestChange = { pairKey, locked ->
                            pendingRelationLockChange = pairKey to locked
                        },
                    )
                }
            }

            item {
                SessionInsights(state.session?.runtimeStateOverview ?: JsonObject(emptyMap()))
            }

            generationStats?.let { insight ->
                item { GenerationInsights(insight) }
            }

            contextUsage?.let { insight ->
                item { ContextUsageInsights(insight) }
            }

            consistency?.let { insight ->
                item { ConsistencyInsights(insight) }
            }

            if (characterArcs.isNotEmpty()) {
                item { CharacterArcInsights(characterArcs) }
            }

            speakerState?.let { insight ->
                item { SpeakerInsights(insight) }
            }

            if (relationTimelines.isNotEmpty()) {
                item { RelationTimelineInsights(relationTimelines) }
            }

            if (eventSignals.isNotEmpty()) {
                item { EventSignalInsights(eventSignals) }
            }
        }
    }
}

@Composable
internal fun ToolSection(
    title: String,
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = COUITheme.textStyles.headlineXS,
                    fontWeight = FontWeight.SemiBold,
                )
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
internal fun ToolButton(
    label: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else icon()
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}

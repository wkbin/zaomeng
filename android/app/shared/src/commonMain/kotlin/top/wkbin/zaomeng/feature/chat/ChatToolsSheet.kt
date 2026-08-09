package top.wkbin.zaomeng.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.data.api.DialogueMemoryDto
import top.wkbin.zaomeng.data.api.ReusableCardDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

private data class DirectorAction(val value: String, val label: String)

private val directorActions = listOf(
    DirectorAction("advance", "推进剧情"),
    DirectorAction("slow_emotion", "放慢情绪"),
    DirectorAction("conflict", "加强冲突"),
    DirectorAction("viewpoint", "切换视角"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatToolsSheet(
    state: ChatUiState,
    onDismiss: () -> Unit,
    onDirector: (String, String) -> Unit,
    onCorrectLatest: () -> Unit,
    onDeepReviewLatest: () -> Unit,
    onBranchTurn: (String) -> Unit,
    onBranchScene: (Int) -> Unit,
    onUpdateBranchMeta: (String, Boolean) -> Unit,
    onToggleMainlineEvent: (String, Boolean) -> Unit,
    onOpenExistingBranch: (String) -> Unit,
    onLoadScenes: () -> Unit,
    onRecommendScene: () -> Unit,
    onSwitchScene: (String, String, Boolean) -> Unit,
    onSaveMemory: (DialogueMemoryDto) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onRelationLock: (String, Boolean) -> Unit,
    onOpenStoryRecap: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var directorDialog by remember { mutableStateOf(false) }
    var directorGoal by remember { mutableStateOf("") }
    var directorAction by remember { mutableStateOf("advance") }
    var memoryDraft by remember { mutableStateOf<DialogueMemoryDto?>(null) }
    var memorySaveBaseline by remember { mutableStateOf<Long?>(null) }
    var pendingMemoryDeletion by remember { mutableStateOf<DialogueMemoryDto?>(null) }
    var pendingRelationLockChange by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var relationLockHelpDialog by remember { mutableStateOf(false) }
    var branchMetaDialog by remember { mutableStateOf(false) }
    var branchLabel by remember(state.session?.sessionId) {
        mutableStateOf(state.session?.branchMeta?.stringValue("label").orEmpty())
    }
    var mainline by remember(state.session?.sessionId) {
        mutableStateOf(state.session?.branchMeta?.booleanValue("is_mainline") == true)
    }
    val toolsEnabled = state.canUseTools
    val session = state.session
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

    if (directorDialog) {
        AlertDialog(
            onDismissRequest = { directorDialog = false },
            title = { Text("剧情导演") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    directorActions.chunked(2).forEach { actions ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            actions.forEach { action ->
                                FilterChip(
                                    selected = directorAction == action.value,
                                    onClick = { directorAction = action.value },
                                    label = { Text(action.label) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = directorGoal,
                        onValueChange = { directorGoal = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("这一幕希望怎样发展") },
                        placeholder = { Text("例如：让两人因为旧事发生正面冲突") },
                        minLines = 3,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (directorGoal.isNotBlank() && toolsEnabled) {
                                    directorDialog = false
                                    onDirector(directorGoal, directorAction)
                                }
                            },
                        ),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        directorDialog = false
                        onDirector(directorGoal, directorAction)
                    },
                    enabled = directorGoal.isNotBlank() && toolsEnabled,
                ) { Text("生成方案") }
            },
            dismissButton = { TextButton(onClick = { directorDialog = false }) { Text("取消") } },
        )
    }

    if (relationLockHelpDialog) {
        AlertDialog(
            onDismissRequest = { relationLockHelpDialog = false },
            title = { Text("会话关系锁") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("锁定后，这对人物在当前会话中的信任、好感、敌意、暧昧及相关关系事件，不会再随后续对话自动变化。")
                    Text("人物仍会继续对话，剧情和人物状态也会正常推进。它不会改写书卷的基础关系资料。")
                    Text("关系锁只作用于当前会话；从这里创建的分支会继承锁定状态。解除后从下一轮起恢复自动演化，锁定期间的变化不会补算。")
                }
            },
            confirmButton = {
                TextButton(onClick = { relationLockHelpDialog = false }) { Text("知道了") }
            },
        )
    }

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

    pendingMemoryDeletion?.let { memory ->
        AlertDialog(
            onDismissRequest = { pendingMemoryDeletion = null },
            title = { Text("删除这条记忆？") },
            text = { Text(memory.text) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingMemoryDeletion = null
                        onDeleteMemory(memory.memoryId)
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingMemoryDeletion = null }) { Text("取消") } },
        )
    }

    pendingRelationLockChange?.let { (pairKey, shouldLock) ->
        AlertDialog(
            onDismissRequest = { pendingRelationLockChange = null },
            title = { Text(if (shouldLock) "锁定人物关系？" else "解除人物关系锁定？") },
            text = {
                Text(
                    if (shouldLock) {
                        "锁定后，后续剧情不会自动改写这组关系：$pairKey"
                    } else {
                        "解除锁定后，后续剧情可以再次更新这组关系：$pairKey"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRelationLockChange = null
                        onRelationLock(pairKey, shouldLock)
                    },
                ) { Text(if (shouldLock) "锁定" else "解除锁定") }
            },
            dismissButton = { TextButton(onClick = { pendingRelationLockChange = null }) { Text("取消") } },
        )
    }

    if (branchMetaDialog) {
        AlertDialog(
            onDismissRequest = { branchMetaDialog = false },
            title = { Text("分支信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = branchLabel,
                        onValueChange = { branchLabel = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("分支名称") },
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = mainline, onCheckedChange = { mainline = it })
                        Text("设为主线分支")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    branchMetaDialog = false
                    onUpdateBranchMeta(branchLabel, mainline)
                }, enabled = toolsEnabled) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { branchMetaDialog = false }) { Text("取消") } },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets.navigationBars },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                top = 4.dp,
                end = 16.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("会话工具", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "当前分支 ${state.sessionId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            item {
                ToolSection("剧情复盘") {
                    ToolButton(
                        label = "查看剧情卡并分享",
                        icon = { Icon(Icons.Filled.History, contentDescription = null) },
                        enabled = toolsEnabled,
                        onClick = onOpenStoryRecap,
                    )
                }
            }

            item {
                ToolSection("最新一轮") {
                    ToolButton(
                        label = "生成修正版",
                        icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                        busy = state.toolBusy == "correct",
                        enabled = toolsEnabled && state.session?.transcript?.isNotEmpty() == true,
                        onClick = onCorrectLatest,
                    )
                    ToolButton(
                        label = "深度复核",
                        icon = { Icon(Icons.AutoMirrored.Outlined.FactCheck, contentDescription = null) },
                        busy = state.toolBusy == "review",
                        enabled = toolsEnabled && state.session?.transcript?.isNotEmpty() == true,
                        onClick = onDeepReviewLatest,
                    )
                }
            }

            item {
                ToolSection("分支") {
                    OutlinedButton(
                        onClick = { branchMetaDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = toolsEnabled,
                    ) {
                        Icon(Icons.Outlined.AccountTree, contentDescription = null)
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
                        OutlinedButton(
                            onClick = onLoadScenes,
                            modifier = Modifier.weight(1f),
                            enabled = toolsEnabled,
                        ) { Text("载入场景卡") }
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
                ToolSection("可控记忆") {
                    OutlinedButton(
                        onClick = { memoryDraft = DialogueMemoryDto() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = toolsEnabled,
                    ) {
                        Icon(Icons.Outlined.BookmarkAdd, contentDescription = null)
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

            state.session?.takeIf { it.relationMatrix.isNotEmpty() }?.let { session ->
                item {
                    ToolSection(
                        title = "会话关系锁",
                        action = {
                            IconButton(onClick = { relationLockHelpDialog = true }) {
                                Icon(Icons.Outlined.Info, contentDescription = "关系锁说明")
                            }
                        },
                    ) {
                        session.relationMatrix.forEach { (matrixKey, element) ->
                            val relation = element as? JsonObject ?: JsonObject(emptyMap())
                            val pairKey = relation.stringValue("pair_key").ifBlank { matrixKey }
                            if (pairKey.isNotBlank()) {
                                val locked = session.relationLocks[pairKey]
                                    ?.jsonPrimitive?.booleanOrNull == true
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            relation.firstString("label", "relationship_type")
                                                .ifBlank { pairKey },
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            pairKey,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(
                                        onClick = { pendingRelationLockChange = pairKey to !locked },
                                        enabled = toolsEnabled,
                                    ) {
                                        Icon(
                                            if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                            contentDescription = if (locked) "解除锁定" else "锁定关系",
                                        )
                                        Spacer(Modifier.size(4.dp))
                                        Text(if (locked) "已锁定" else "锁定")
                                    }
                                }
                            }
                        }
                    }
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
private fun ToolSection(
    title: String,
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun ToolButton(
    label: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else icon()
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}

@Composable
private fun BranchRow(title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(Icons.Outlined.AccountTree, contentDescription = "创建分支")
        }
    }
}

@Composable
private fun EventBranchRow(
    title: String,
    detail: String,
    locked: Boolean,
    enabled: Boolean,
    onBranch: () -> Unit,
    onToggleLock: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onBranch, enabled = enabled) {
            Icon(Icons.Outlined.AccountTree, contentDescription = "从事件创建分支")
        }
        IconButton(onClick = { onToggleLock(!locked) }, enabled = enabled) {
            Icon(
                if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                contentDescription = if (locked) "解除主线事件锁定" else "锁定为主线事件",
            )
        }
    }
}

@Composable
private fun BranchGraphInsights(
    nodes: List<BranchNodeInsight>,
    enabled: Boolean,
    onOpenBranch: (String) -> Unit,
) {
    ToolSection("现有分支") {
        nodes.forEachIndexed { index, node ->
            TextButton(
                onClick = { onOpenBranch(node.sessionId) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !node.isCurrent && node.sessionId.isNotBlank(),
            ) {
                Icon(Icons.Outlined.AccountTree, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        node.label,
                        fontWeight = if (node.isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        node.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    when {
                        node.isCurrent -> "当前"
                        node.isMainline -> "主线"
                        else -> "打开"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (index != nodes.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun ConsistencyInsights(insight: ConsistencyInsight) {
    ToolSection("一致性监控") {
        val metrics = buildList {
            if (insight.checkedTurns > 0) add("已检查 ${insight.checkedTurns} 轮")
            insight.averageScore?.let { add("平均 $it 分") }
            insight.passRate?.let { add("通过率 $it%") }
            if (insight.issueCount > 0) add("${insight.issueCount} 项问题")
            if (insight.passStreak > 0) add("连续通过 ${insight.passStreak} 轮")
        }
        Text(
            listOf(insight.statusLabel, metrics.joinToString(" · "))
                .filter(String::isNotBlank)
                .joinToString(" · "),
            color = if (insight.issueCount > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            fontWeight = FontWeight.Medium,
        )
        if (insight.summary.isNotBlank()) {
            Text(insight.summary, style = MaterialTheme.typography.bodyMedium)
        }
        insight.latestIssues.forEach { issue ->
            Text(
                "• $issue",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CharacterArcInsights(arcs: List<CharacterArcInsight>) {
    ToolSection("人物状态弧线") {
        arcs.forEachIndexed { index, arc ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(arc.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    if (arc.changeCount > 0) {
                        Text(
                            "${arc.changeCount} 次变化",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (arc.stateSummary.isNotBlank()) {
                    Text(arc.stateSummary, style = MaterialTheme.typography.bodySmall)
                }
                if (arc.growthSummary.isNotBlank()) {
                    Text(
                        arc.growthSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (arc.latestReason.isNotBlank() && arc.latestReason != arc.growthSummary) {
                    Text(
                        "最近：${arc.latestReason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (index != arcs.lastIndex) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun SpeakerInsights(insight: SpeakerInsight) {
    ToolSection("发言节奏") {
        if (insight.recommendedSpeakers.isNotEmpty()) {
            Text(
                "建议下一轮：${insight.recommendedSpeakers.joinToString("、")}",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
            insight.recommendedSpeakers.forEach { name ->
                insight.reasons[name]?.takeIf(String::isNotBlank)?.let { reason ->
                    Text(
                        "$name：$reason",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (insight.activity.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
        insight.activity.forEachIndexed { index, activity ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(activity.name, fontWeight = FontWeight.Medium)
                    Text(
                        activity.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    activity.statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (activity.needsAttention) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            if (index != insight.activity.lastIndex) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun RelationTimelineInsights(relations: List<RelationTimelineInsight>) {
    ToolSection("关系变化") {
        relations.forEachIndexed { index, relation ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(relation.label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    if (relation.locked) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = "关系已锁定",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (relation.currentSummary.isNotBlank()) {
                    Text(relation.currentSummary, style = MaterialTheme.typography.bodySmall)
                }
                if (relation.changeSummary.isNotBlank()) {
                    Text(
                        relation.changeSummary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (relation.reason.isNotBlank()) {
                    Text(relation.reason, style = MaterialTheme.typography.bodySmall)
                }
                if (relation.evidence.isNotBlank()) {
                    Text(
                        "依据：${relation.evidence}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (index != relations.lastIndex) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun EventSignalInsights(events: List<EventSignalInsight>) {
    ToolSection("近期事件信号") {
        events.forEachIndexed { index, event ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(event.kindLabel, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                Text(event.cue, style = MaterialTheme.typography.bodyMedium)
                if (event.context.isNotBlank()) {
                    Text(
                        event.context,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (index != events.lastIndex) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun SceneRow(
    card: ReusableCardDto,
    recommended: Boolean,
    enabled: Boolean,
    transition: String,
    onSwitch: (String, String, Boolean) -> Unit,
) {
    val title = card.preview.stringValue("title").ifBlank { card.fields.stringValue("title") }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { "未命名场景" }, fontWeight = FontWeight.Medium)
                Text(
                    listOf(card.preview.stringValue("location"), card.preview.stringValue("atmosphere"))
                        .filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (recommended) {
                Text(
                    "推荐",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onSwitch(card.cardId, transition, false) },
                modifier = Modifier.weight(1f),
                enabled = enabled && transition.isNotBlank(),
            ) { Text("切换") }
            Button(
                onClick = { onSwitch(card.cardId, transition, true) },
                modifier = Modifier.weight(1f),
                enabled = enabled && transition.isNotBlank(),
            ) { Text("切换并续写") }
        }
    }
}

@Composable
private fun MemoryRow(
    memory: DialogueMemoryDto,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(memory.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    "${memory.category.memoryCategoryLabel()}${if (memory.pinned) " · 已置顶" else ""}${if (!memory.enabled) " · 已停用" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit, enabled = enabled) { Text("编辑") }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除记忆",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun MemoryEditor(
    memory: DialogueMemoryDto,
    busy: Boolean,
    enabled: Boolean,
    onChange: (DialogueMemoryDto) -> Unit,
    onSave: (DialogueMemoryDto) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (memory.memoryId.isBlank()) "添加记忆" else "编辑记忆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = memory.text,
                    onValueChange = { onChange(memory.copy(text = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    label = { Text("记忆内容") },
                    minLines = 3,
                    maxLines = 6,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("story", "relationship", "short_term", "long_term").forEach { category ->
                        FilterChip(
                            selected = memory.category == category,
                            onClick = { onChange(memory.copy(category = category)) },
                            enabled = enabled,
                            label = { Text(category.memoryCategoryLabel()) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = memory.pinned,
                        onCheckedChange = { onChange(memory.copy(pinned = it)) },
                        enabled = enabled,
                    )
                    Text("置顶")
                    Checkbox(
                        checked = memory.enabled,
                        onCheckedChange = { onChange(memory.copy(enabled = it)) },
                        enabled = enabled,
                    )
                    Text("启用")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(memory) }, enabled = memory.text.isNotBlank() && enabled) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

@Composable
private fun SessionInsights(overview: JsonObject) {
    val rows = listOf(
        "地点" to overview.firstString("location", "current_location"),
        "时间" to overview.firstString("time_hint", "current_time"),
        "气氛" to overview.firstString("atmosphere", "atmosphere_summary"),
        "张力" to overview.firstString("tension", "tension_summary"),
    ).filter { it.second.isNotBlank() }
    if (rows.isEmpty()) return
    ToolSection("当前场景状态") {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth()) {
                Text(label, modifier = Modifier.padding(end = 12.dp), color = MaterialTheme.colorScheme.primary)
                Text(value, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GenerationInsights(insight: GenerationInsight) {
    ToolSection("模型调用") {
        val modelLabel = listOf(insight.provider, insight.model)
            .filter(String::isNotBlank)
            .joinToString(" · ")
        if (modelLabel.isNotBlank()) {
            Text(modelLabel, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
        val latestMetrics = buildList {
            if (insight.promptTokens > 0) add("输入 ${insight.promptTokens.compactCount()}")
            if (insight.completionTokens > 0) add("输出 ${insight.completionTokens.compactCount()}")
            if (insight.totalTokens > 0) add("合计 ${insight.totalTokens.compactCount()} Token")
            if (insight.elapsedSeconds > 0.0) add("耗时 ${insight.elapsedSeconds.displaySeconds()}")
            if (insight.attemptCount > 1) add("${insight.attemptCount} 次尝试")
        }
        if (latestMetrics.isNotEmpty()) {
            Text("本轮：${latestMetrics.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
        }
        val cacheText = insight.cacheDescription()
        if (cacheText.isNotBlank()) {
            Text(
                cacheText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val sessionMetrics = buildList {
            if (insight.sessionTurns > 0) add("${insight.sessionTurns} 轮")
            if (insight.sessionTokens > 0) add("${insight.sessionTokens.compactCount()} Token")
            if (insight.sessionElapsedSeconds > 0.0) add("${insight.sessionElapsedSeconds.displaySeconds()}")
            if (insight.sessionRetryCount > 0) add("重试 ${insight.sessionRetryCount} 次")
        }
        if (sessionMetrics.isNotEmpty()) {
            Text(
                "本会话：${sessionMetrics.joinToString(" · ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ContextUsageInsights(insight: ContextUsageInsight) {
    ToolSection("本轮上下文") {
        insight.speaker.takeIf(String::isNotBlank)?.let { speaker ->
            Text("由 $speaker 发起", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
        insight.sources.forEachIndexed { index, source ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${source.label} · ${source.count} 项", fontWeight = FontWeight.Medium)
                if (source.items.isNotEmpty()) {
                    Text(
                        source.items.joinToString("；"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (index != insight.sources.lastIndex) HorizontalDivider(Modifier.padding(vertical = 3.dp))
        }
    }
}

internal data class BranchNodeInsight(
    val sessionId: String,
    val label: String,
    val detail: String,
    val isCurrent: Boolean,
    val isMainline: Boolean,
)

internal data class ConsistencyInsight(
    val statusLabel: String,
    val checkedTurns: Int,
    val issueCount: Int,
    val averageScore: Int?,
    val passRate: Int?,
    val passStreak: Int,
    val summary: String,
    val latestIssues: List<String>,
)

internal data class CharacterArcInsight(
    val name: String,
    val stateSummary: String,
    val growthSummary: String,
    val latestReason: String,
    val changeCount: Int,
)

internal data class SpeakerActivityInsight(
    val name: String,
    val statusLabel: String,
    val detail: String,
    val needsAttention: Boolean,
)

internal data class SpeakerInsight(
    val recommendedSpeakers: List<String>,
    val reasons: Map<String, String>,
    val activity: List<SpeakerActivityInsight>,
)

internal data class RelationTimelineInsight(
    val label: String,
    val locked: Boolean,
    val currentSummary: String,
    val changeSummary: String,
    val reason: String,
    val evidence: String,
)

internal data class EventSignalInsight(
    val kindLabel: String,
    val cue: String,
    val context: String,
)

internal data class GenerationInsight(
    val provider: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val elapsedSeconds: Double,
    val attemptCount: Int,
    val cacheObserved: Boolean,
    val cacheStatus: String,
    val cacheHitRate: Double?,
    val sessionTurns: Int,
    val sessionTokens: Int,
    val sessionElapsedSeconds: Double,
    val sessionRetryCount: Int,
)

internal data class ContextUsageInsight(
    val speaker: String,
    val sources: List<ContextSourceInsight>,
)

internal data class ContextSourceInsight(
    val label: String,
    val count: Int,
    val items: List<String>,
)

internal fun JsonObject.branchNodeInsights(): List<BranchNodeInsight> {
    val currentSessionId = stringValue("current_session_id")
    return objectList("nodes").mapIndexedNotNull { index, node ->
        val sessionId = node.stringValue("session_id")
        if (sessionId.isBlank()) return@mapIndexedNotNull null
        val origin = node.stringValue("origin_title").ifBlank {
            when (node.stringValue("origin_kind")) {
                "scene_timeline" -> "来自场景分支"
                "event_timeline", "turn" -> "来自事件分支"
                "correction" -> "来自修正版"
                "root" -> "起始分支"
                else -> ""
            }
        }
        val detail = buildList {
            origin.takeIf(String::isNotBlank)?.let { add(it) }
            node.intValue("event_count")?.takeIf { it > 0 }?.let { add("$it 个事件") }
        }.joinToString(" · ").ifBlank { sessionId }
        BranchNodeInsight(
            sessionId = sessionId,
            label = node.stringValue("label").ifBlank { "分支 ${index + 1}" },
            detail = detail,
            isCurrent = node.booleanValue("is_current") || sessionId == currentSessionId,
            isMainline = node.booleanValue("is_mainline"),
        )
    }
}

internal fun JsonObject.consistencyInsight(): ConsistencyInsight? {
    if (isEmpty()) return null
    val latest = objectValue("latest").takeIf { it.isNotEmpty() } ?: this
    val metrics = objectValue("metrics")
    val issues = latest.objectList("issues")
    val issueCount = metrics.intValue("total_issues")
        ?: intValue("issue_count")
        ?: intValue("warning_count")
        ?: issues.size
    return ConsistencyInsight(
        statusLabel = when (latest.stringValue("status").ifBlank { stringValue("status") }) {
            "pass" -> "状态正常"
            "warning" -> "需要复核"
            "reviewed" -> "已复核"
            "error" -> "存在错误"
            else -> "已有检查记录"
        },
        checkedTurns = metrics.intValue("checked_turns") ?: intValue("checked_turns") ?: 0,
        issueCount = issueCount,
        averageScore = metrics.intValue("average_score"),
        passRate = metrics.intValue("pass_rate"),
        passStreak = metrics.intValue("current_pass_streak") ?: 0,
        summary = latest.firstString("summary", "message"),
        latestIssues = issues.mapNotNull { issue ->
            issue.firstString("detail", "message", "summary", "code").takeIf(String::isNotBlank)
        }.take(4),
    )
}

internal fun List<JsonObject>.characterArcInsights(): List<CharacterArcInsight> = mapNotNull { arc ->
    val name = arc.stringValue("name")
    if (name.isBlank()) return@mapNotNull null
    val points = arc.objectList("points")
    val latestPoint = points.lastOrNull() ?: JsonObject(emptyMap())
    val current = arc.objectValue("current").takeIf { it.isNotEmpty() }
        ?: latestPoint.objectValue("state")
    val stateSummary = characterStateLabels.mapNotNull { (key, label) ->
        current.stringValue(key).takeIf(String::isNotBlank)?.let { "$label：$it" }
    }.joinToString(" · ")
    CharacterArcInsight(
        name = name,
        stateSummary = stateSummary,
        growthSummary = arc.stringValue("growth_summary"),
        latestReason = latestPoint.stringValue("reason"),
        changeCount = arc.intValue("change_count") ?: (points.size - 1).coerceAtLeast(0),
    )
}

internal fun speakerInsights(
    activityPayload: List<JsonObject>,
    balance: JsonObject,
): SpeakerInsight? {
    val recommended = balance.stringList("recommended_speakers")
    val reasons = balance.objectValue("reasons").mapNotNull { (name, value) ->
        value.primitiveStringOrNull()?.takeIf(String::isNotBlank)?.let { name to it }
    }.toMap()
    val activity = activityPayload.mapNotNull { item ->
        val name = item.stringValue("name")
        if (name.isBlank()) return@mapNotNull null
        val status = item.stringValue("status")
        val totalTurns = item.intValue("total_turns") ?: 0
        val spokenTurns = item.intValue("spoken_turns") ?: 0
        val replyCount = item.intValue("reply_count") ?: 0
        val turnsSinceSpoke = item.intValue("turns_since_spoke") ?: 0
        val participation = item.doubleValue("participation_rate")
            ?.let { (it.coerceIn(0.0, 1.0) * 100).roundToInt() }
        val detail = buildList {
            if (totalTurns > 0) add("$spokenTurns/$totalTurns 轮参与")
            if (replyCount > spokenTurns) add("$replyCount 条回复")
            if (turnsSinceSpoke > 0) add("$turnsSinceSpoke 轮未发言")
            participation?.let { add("参与率 $it%") }
        }.joinToString(" · ").ifBlank { "等待产生会话记录" }
        SpeakerActivityInsight(
            name = name,
            statusLabel = when (status) {
                "new" -> "等待首次发言"
                "active" -> "近期活跃"
                "due" -> "可以介入"
                "silent" -> "沉默较久"
                else -> "状态未知"
            },
            detail = detail,
            needsAttention = status == "due" || status == "silent",
        )
    }
    if (recommended.isEmpty() && activity.isEmpty()) return null
    return SpeakerInsight(recommendedSpeakers = recommended, reasons = reasons, activity = activity)
}

internal fun List<JsonObject>.relationTimelineInsights(): List<RelationTimelineInsight> = mapNotNull { relation ->
    val pairKey = relation.stringValue("pair_key")
    val label = relation.stringValue("label")
        .ifBlank { relation.stringList("characters").joinToString(" · ") }
        .ifBlank { pairKey }
    if (label.isBlank()) return@mapNotNull null
    val current = relation.objectValue("current")
    val points = relation.objectList("points")
    val latest = points.lastOrNull() ?: JsonObject(emptyMap())
    val changes = latest.objectValue("changes")
    RelationTimelineInsight(
        label = label,
        locked = relation.booleanValue("locked"),
        currentSummary = relationMetrics.mapNotNull { (key, metricLabel) ->
            current.intValue(key)?.let { "$metricLabel $it" }
        }.joinToString(" · "),
        changeSummary = relationMetrics.mapNotNull { (key, metricLabel) ->
            changes.intValue(key)?.takeIf { it != 0 }?.let { delta ->
                "$metricLabel ${if (delta > 0) "+$delta" else delta.toString()}"
            }
        }.joinToString(" · "),
        reason = latest.stringValue("reason"),
        evidence = latest.stringValue("evidence"),
    )
}

internal fun JsonObject.eventSignalInsights(): List<EventSignalInsight> = objectList("recent")
    .takeLast(8)
    .asReversed()
    .mapNotNull { event ->
        // 新格式：事件信号统一为 kind / cue（旧 event_type/summary 不再兼容）
        val kind = event.stringValue("kind")
        val cue = event.stringValue("cue")
        if (kind.isBlank() || cue.isBlank()) return@mapNotNull null
        val actor = event.stringValue("actor")
        val target = event.stringValue("target")
        val actorTarget = when {
            actor.isNotBlank() && target.isNotBlank() -> "$actor → $target"
            actor.isNotBlank() -> actor
            target.isNotBlank() -> "涉及 $target"
            else -> ""
        }
        EventSignalInsight(
            kindLabel = kind.eventKindLabel(),
            cue = cue,
            context = listOf(
                actorTarget,
                event.stringValue("time_hint"),
                event.stringValue("location_hint"),
            ).filter(String::isNotBlank).joinToString(" · "),
        )
    }

internal fun JsonObject.generationInsight(): GenerationInsight? {
    val latest = objectValue("latest")
    val session = objectValue("session")
    if (latest.isEmpty() && session.isEmpty()) return null
    val totalTokens = latest.intValue("total_tokens") ?: 0
    val sessionTokens = session.intValue("total_tokens") ?: 0
    val sessionTurns = session.intValue("total_turns") ?: 0
    if (latest.isEmpty() && totalTokens == 0 && sessionTokens == 0 && sessionTurns == 0) return null
    return GenerationInsight(
        provider = latest.stringValue("provider"),
        model = latest.stringValue("model"),
        promptTokens = latest.intValue("prompt_tokens") ?: 0,
        completionTokens = latest.intValue("completion_tokens") ?: 0,
        totalTokens = totalTokens,
        elapsedSeconds = latest.doubleValue("elapsed_seconds") ?: 0.0,
        attemptCount = latest.intValue("attempt_count") ?: 0,
        cacheObserved = latest.booleanValue("observed"),
        cacheStatus = latest.stringValue("status"),
        cacheHitRate = latest.doubleValue("hit_rate"),
        sessionTurns = sessionTurns,
        sessionTokens = sessionTokens,
        sessionElapsedSeconds = session.doubleValue("elapsed_seconds") ?: 0.0,
        sessionRetryCount = session.intValue("retry_count") ?: 0,
    )
}

internal fun JsonObject.contextUsageInsight(): ContextUsageInsight? {
    val sources = objectList("sources").mapNotNull { source ->
        val label = source.stringValue("label")
        val count = source.intValue("count") ?: 0
        if (label.isBlank() || count <= 0) return@mapNotNull null
        ContextSourceInsight(
            label = label,
            count = count,
            items = source.stringList("items").take(2),
        )
    }
    if (sources.isEmpty()) return null
    return ContextUsageInsight(speaker = stringValue("speaker"), sources = sources)
}

private fun GenerationInsight.cacheDescription(): String = when {
    !cacheObserved -> "缓存：当前模型未返回可观测数据"
    cacheHitRate != null -> "缓存：${cacheStatus.cacheStatusLabel()}，命中 ${(cacheHitRate * 100).roundToInt()}%"
    else -> "缓存：${cacheStatus.cacheStatusLabel()}"
}

private fun String.cacheStatusLabel(): String = when (this) {
    "hit" -> "已命中"
    "write" -> "已写入"
    "miss" -> "未命中"
    "partial" -> "部分可观测"
    else -> "状态未知"
}

private fun Int.compactCount(): String = when {
    this < 1_000 -> toString()
    else -> "${this / 1_000}.${(this % 1_000) / 100}k"
}

private fun Double.displaySeconds(): String = when {
    this >= 60.0 -> "${roundToInt()} 秒"
    else -> "${((this * 10).roundToInt() / 10.0)} 秒"
}

private val characterStateLabels = listOf(
    "mood" to "情绪",
    "interaction_state" to "立场",
    "focus" to "目标",
    "last_target" to "关注对象",
    "present_state" to "在场状态",
    "scene_location" to "位置",
)

private val relationMetrics = listOf(
    "trust" to "信任",
    "affection" to "情感",
    "hostility" to "敌意",
    "ambiguity" to "暧昧",
)

private fun String.eventKindLabel(): String = when (this) {
    "scene_transition" -> "转场"
    "cast_enter" -> "入场"
    "cast_exit" -> "离场"
    "atmosphere_shift" -> "气氛变化"
    "time_change" -> "时间推进"
    "environment_change" -> "环境变化"
    "beat_complete" -> "一拍收束"
    "relationship_shift" -> "关系变化"
    "micro_action" -> "细微动作"
    else -> this.ifBlank { "事件" }
}

private fun JsonObject.stringValue(key: String): String = this[key]
    ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    .orEmpty()

private fun JsonObject.firstString(vararg keys: String): String = keys
    .firstNotNullOfOrNull { key -> stringValue(key).takeIf(String::isNotBlank) }
    .orEmpty()

private fun JsonObject.booleanValue(key: String): Boolean = this[key]
    ?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
    ?: false

private fun JsonObject.intValue(key: String): Int? = this[key]
    ?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }

private fun JsonObject.doubleValue(key: String): Double? = this[key]
    ?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }

private fun JsonObject.objectValue(key: String): JsonObject = this[key]
    ?.let { runCatching { it.jsonObject }.getOrNull() }
    ?: JsonObject(emptyMap())

private fun JsonObject.objectList(key: String): List<JsonObject> = this[key]
    ?.let { runCatching { it.jsonArray }.getOrNull() }
    ?.mapNotNull { element -> runCatching { element.jsonObject }.getOrNull() }
    .orEmpty()

private fun JsonObject.stringList(key: String): List<String> = this[key]
    ?.let { runCatching { it.jsonArray }.getOrNull() }
    ?.mapNotNull { it.primitiveStringOrNull() }
    ?.filter(String::isNotBlank)
    .orEmpty()

private fun JsonElement.primitiveStringOrNull(): String? = runCatching {
    jsonPrimitive.contentOrNull
}.getOrNull()

private fun String.memoryCategoryLabel(): String = when (this) {
    "relationship" -> "关系"
    "short_term" -> "短期"
    "long_term" -> "长期"
    else -> "剧情"
}

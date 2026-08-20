package top.wkbin.zaomeng.feature.chat
import top.wkbin.zaomeng.feature.chat.insights.*

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
    DirectorAction("fourth_wall", "第四面墙"),
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
    onUpdateAutomaticMemoryStatus: (String, String) -> Unit,
    onMergeDuplicateMemories: () -> Unit,
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
            title = { Text(if (directorAction == "fourth_wall") "第四面墙" else "剧情导演") },
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
                        label = { Text(if (directorAction == "fourth_wall") "以作者身份对角色下什么指令" else "这一幕希望怎样发展") },
                        placeholder = { Text(if (directorAction == "fourth_wall") "例如：让他们和好，否则我删掉这段记忆" else "例如：让两人因为旧事发生正面冲突") },
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
                ToolSection("用户固定记忆") {
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

            item {
                val quality = state.memoryQuality
                val automatic = quality.entries.filter { it.source == "automatic" }
                ToolSection("自动记忆质量") {
                    Text(
                        "启用 ${quality.activeCount} · 过期 ${quality.staleCount} · 冲突 ${quality.conflictCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (quality.duplicateGroups.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onMergeDuplicateMemories,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = toolsEnabled,
                        ) {
                            Text("合并 ${quality.duplicateGroups.size} 组重复记忆")
                        }
                    }
                    val currentHits = automatic.filter {
                        quality.latestHitTurnId.isNotBlank() && it.lastHitTurnId == quality.latestHitTurnId
                    }
                    if (currentHits.isNotEmpty()) {
                        Text("本轮命中", style = MaterialTheme.typography.titleSmall)
                        currentHits.forEach { memory ->
                            AutomaticMemoryRow(memory, toolsEnabled, onUpdateAutomaticMemoryStatus)
                        }
                    }
                    val remaining = automatic.filterNot { it in currentHits }
                    if (remaining.isNotEmpty()) {
                        Text("全部自动记忆", style = MaterialTheme.typography.titleSmall)
                        remaining.takeLast(40).asReversed().forEach { memory ->
                            AutomaticMemoryRow(memory, toolsEnabled, onUpdateAutomaticMemoryStatus)
                        }
                    } else if (automatic.isEmpty()) {
                        Text(
                            "完成对话后会自动建立带来源轮次的本地记忆。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
internal fun ToolSection(
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
internal fun ToolButton(
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

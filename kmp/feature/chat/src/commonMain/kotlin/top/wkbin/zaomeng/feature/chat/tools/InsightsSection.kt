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

@Composable
internal fun ConsistencyInsights(insight: ConsistencyInsight) {
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
internal fun CharacterArcInsights(arcs: List<CharacterArcInsight>) {
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
internal fun SpeakerInsights(insight: SpeakerInsight) {
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
internal fun RelationTimelineInsights(relations: List<RelationTimelineInsight>) {
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
internal fun EventSignalInsights(events: List<EventSignalInsight>) {
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
internal fun SessionInsights(overview: JsonObject) {
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
internal fun GenerationInsights(insight: GenerationInsight) {
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
internal fun ContextUsageInsights(insight: ContextUsageInsight) {
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

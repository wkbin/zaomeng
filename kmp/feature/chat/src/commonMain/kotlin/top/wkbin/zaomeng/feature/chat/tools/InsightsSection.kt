package top.wkbin.zaomeng.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Lock
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.serialization.json.JsonObject
import top.wkbin.zaomeng.feature.chat.insights.*

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
                COUITheme.colorScheme.error
            } else {
                COUITheme.colorScheme.primary
            },
            fontWeight = FontWeight.Medium,
        )
        if (insight.summary.isNotBlank()) {
            Text(insight.summary, style = COUITheme.textStyles.bodyM)
        }
        insight.latestIssues.forEach { issue ->
            Text(
                "• $issue",
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.labelSecondary,
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
                            style = COUITheme.textStyles.description,
                            color = COUITheme.colorScheme.primary,
                        )
                    }
                }
                if (arc.stateSummary.isNotBlank()) {
                    Text(arc.stateSummary, style = COUITheme.textStyles.bodyXS)
                }
                if (arc.growthSummary.isNotBlank()) {
                    Text(
                        arc.growthSummary,
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
                if (arc.latestReason.isNotBlank() && arc.latestReason != arc.growthSummary) {
                    Text(
                        "最近：${arc.latestReason}",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
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
                color = COUITheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
            insight.recommendedSpeakers.forEach { name ->
                insight.reasons[name]?.takeIf(String::isNotBlank)?.let { reason ->
                    Text(
                        "$name：$reason",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
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
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                    )
                }
                Text(
                    activity.statusLabel,
                    style = COUITheme.textStyles.description,
                    color = if (activity.needsAttention) {
                        COUITheme.colorScheme.error
                    } else {
                        COUITheme.colorScheme.primary
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
                            COUIIcons.Lock,
                            contentDescription = "关系已锁定",
                            tint = COUITheme.colorScheme.primary,
                        )
                    }
                }
                if (relation.currentSummary.isNotBlank()) {
                    Text(relation.currentSummary, style = COUITheme.textStyles.bodyXS)
                }
                if (relation.changeSummary.isNotBlank()) {
                    Text(
                        relation.changeSummary,
                        style = COUITheme.textStyles.description,
                        color = COUITheme.colorScheme.primary,
                    )
                }
                if (relation.reason.isNotBlank()) {
                    Text(relation.reason, style = COUITheme.textStyles.bodyXS)
                }
                if (relation.evidence.isNotBlank()) {
                    Text(
                        "依据：${relation.evidence}",
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
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
                Text(event.kindLabel, color = COUITheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                Text(event.cue, style = COUITheme.textStyles.bodyM)
                if (event.context.isNotBlank()) {
                    Text(
                        event.context,
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
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
                Text(label, modifier = Modifier.padding(end = 12.dp), color = COUITheme.colorScheme.primary)
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
            Text(modelLabel, color = COUITheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
        val latestMetrics = buildList {
            if (insight.promptTokens > 0) add("输入 ${insight.promptTokens.compactCount()}")
            if (insight.completionTokens > 0) add("输出 ${insight.completionTokens.compactCount()}")
            if (insight.totalTokens > 0) add("合计 ${insight.totalTokens.compactCount()} Token")
            if (insight.elapsedSeconds > 0.0) add("耗时 ${insight.elapsedSeconds.displaySeconds()}")
            if (insight.attemptCount > 1) add("${insight.attemptCount} 次尝试")
        }
        if (latestMetrics.isNotEmpty()) {
            Text("本轮：${latestMetrics.joinToString(" · ")}", style = COUITheme.textStyles.bodyXS)
        }
        val cacheText = insight.cacheDescription()
        if (cacheText.isNotBlank()) {
            Text(
                cacheText,
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.labelSecondary,
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
                style = COUITheme.textStyles.bodyXS,
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
    }
}

@Composable
internal fun ContextUsageInsights(insight: ContextUsageInsight) {
    ToolSection("本轮上下文") {
        insight.speaker.takeIf(String::isNotBlank)?.let { speaker ->
            Text("由 $speaker 发起", color = COUITheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
        insight.sources.forEachIndexed { index, source ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${source.label} · ${source.count} 项", fontWeight = FontWeight.Medium)
                if (source.items.isNotEmpty()) {
                    Text(
                        source.items.joinToString("；"),
                        style = COUITheme.textStyles.bodyXS,
                        color = COUITheme.colorScheme.labelSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (index != insight.sources.lastIndex) HorizontalDivider(Modifier.padding(vertical = 3.dp))
        }
    }
}

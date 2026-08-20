package top.wkbin.zaomeng.feature.chat.insights

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

internal fun GenerationInsight.cacheDescription(): String = when {
    !cacheObserved -> "缓存：当前模型未返回可观测数据"
    cacheHitRate != null -> "缓存：${cacheStatus.cacheStatusLabel()}，命中 ${(cacheHitRate * 100).roundToInt()}%"
    else -> "缓存：${cacheStatus.cacheStatusLabel()}"
}

internal fun String.cacheStatusLabel(): String = when (this) {
    "hit" -> "已命中"
    "write" -> "已写入"
    "miss" -> "未命中"
    "partial" -> "部分可观测"
    else -> "状态未知"
}

internal fun Int.compactCount(): String = when {
    this < 1_000 -> toString()
    else -> "${this / 1_000}.${(this % 1_000) / 100}k"
}

internal fun Double.displaySeconds(): String = when {
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

internal fun String.eventKindLabel(): String = when (this) {
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

internal fun JsonObject.stringValue(key: String): String = this[key]
    ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    .orEmpty()

internal fun JsonObject.firstString(vararg keys: String): String = keys
    .firstNotNullOfOrNull { key -> stringValue(key).takeIf(String::isNotBlank) }
    .orEmpty()

internal fun JsonObject.booleanValue(key: String): Boolean = this[key]
    ?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
    ?: false

internal fun JsonObject.intValue(key: String): Int? = this[key]
    ?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }

internal fun JsonObject.doubleValue(key: String): Double? = this[key]
    ?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }

internal fun JsonObject.objectValue(key: String): JsonObject = this[key]
    ?.let { runCatching { it.jsonObject }.getOrNull() }
    ?: JsonObject(emptyMap())

internal fun JsonObject.objectList(key: String): List<JsonObject> = this[key]
    ?.let { runCatching { it.jsonArray }.getOrNull() }
    ?.mapNotNull { element -> runCatching { element.jsonObject }.getOrNull() }
    .orEmpty()

internal fun JsonObject.stringList(key: String): List<String> = this[key]
    ?.let { runCatching { it.jsonArray }.getOrNull() }
    ?.mapNotNull { it.primitiveStringOrNull() }
    ?.filter(String::isNotBlank)
    .orEmpty()

internal fun JsonElement.primitiveStringOrNull(): String? = runCatching {
    jsonPrimitive.contentOrNull
}.getOrNull()

internal fun String.memoryCategoryLabel(): String = when (this) {
    "relationship" -> "关系"
    "short_term" -> "短期"
    "long_term" -> "长期"
    else -> "剧情"
}

@file:OptIn(kotlin.time.ExperimentalTime::class)

package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Instant
import top.wkbin.zaomeng.platform.nowIsoString

/**
 * 会话场景进度状态机（迁移自 Python src/web/chat/scene_signals.py +
 * event_signals.py + scene_progress.py + state_utils.py）。
 *
 * 每轮对话提交后调用 [deriveSceneProgressState] 计算并持久化到 session 的
 * `state` 字段（结构对齐 Python empty_session_state：scene/presence/progression/
 * signals/relations/characters/memory），供对话 payload 的 scene_progress、
 * event_signals、character_snapshots 使用。
 */
object SceneProgressState {

    val ATMOSPHERE_TOKENS = listOf("暧昧", "尴尬", "紧张", "安静", "压抑", "冷场", "发僵", "僵住", "沉下来", "静了一拍", "气氛")
    private val LEAVE_TOKENS = listOf(
        "离开", "离席", "退场", "告退", "先走", "走吧", "退下", "走了", "离去", "回房", "回家", "回去了", "退出",
    )
    private val RETURN_TOKENS = listOf(
        "回来", "回来了", "折返", "再入", "再至", "现身", "又到了", "入场", "进来", "进门", "重回",
    )
    private val TIME_HINT_SEQUENCE = listOf(
        "拂晓", "清晨", "早晨", "上午", "中午", "午后", "下午", "傍晚", "黄昏", "晚上", "入夜", "夜里", "夜深", "深夜", "半夜", "凌晨", "天亮",
    )
    private val TIME_HINT_ALIASES = mapOf(
        "早上" to "早晨", "晌午" to "中午", "今晚" to "晚上", "夜间" to "夜里", "入夜" to "晚上",
        "更深" to "夜深", "三更" to "夜深", "四更" to "深夜", "五更" to "凌晨",
    )
    private val TIME_FORWARD_CUES = listOf(
        "掌灯" to "晚上", "灯都亮了" to "晚上", "天色暗了" to "傍晚", "天都黑了" to "晚上",
        "夜色深了" to "夜深", "夜更深了" to "夜深", "已到深夜" to "深夜", "已近凌晨" to "凌晨",
    )
    private val TIME_DRIFT_CUES = listOf("过了一会", "过了许久", "片刻后", "半晌", "良久", "随后", "一阵后", "再过一阵", "不多时")

    private val SELF_EXIT_SIGNALS = listOf("我先走", "我先告退", "我先退下", "我先回房", "我先回家", "我先离开", "我先撤了", "容我告退")
    private val SELF_RETURN_SIGNALS = listOf("我回来了", "我又回来了", "我进门了", "我回到这里", "我回来了，", "我回来了。")

    // ------------------------------------------------------------------
    // 通用工具
    // ------------------------------------------------------------------

    fun trimSummaryText(value: String, limit: Int): String {
        val text = value.split(Regex("\\s+")).joinToString(" ").trim()
        if (text.isEmpty()) return ""
        if (text.length <= limit) return text
        return text.take(limit) + "..."
    }

    private fun isSceneLevelEntry(item: Map<String, Any?>): Boolean {
        val speaker = item["speaker"]?.toString()?.trim().orEmpty()
        val role = item["role"]?.toString()?.trim().orEmpty()
        return speaker in setOf("旁白", "场景提示") || (speaker.isEmpty() && role == "scene")
    }

    private fun timestampMillis(value: String): Long? {
        val text = value.trim()
        if (text.isEmpty()) return null
        return runCatching { Instant.parse(text).toEpochMilliseconds() }.getOrNull()
    }

    // ------------------------------------------------------------------
    // 时间提示（scene_signals.py）
    // ------------------------------------------------------------------

    fun canonicalTimeHint(value: String): String {
        val text = value.trim()
        if (text.isEmpty()) return ""
        return TIME_HINT_ALIASES[text] ?: text
    }

    fun timeHintRank(value: String): Int {
        val index = TIME_HINT_SEQUENCE.indexOf(canonicalTimeHint(value))
        return if (index >= 0) index else -1
    }

    fun advanceTimeHint(value: String): String {
        val canonical = canonicalTimeHint(value)
        val rank = TIME_HINT_SEQUENCE.indexOf(canonical)
        if (rank < 0) return canonical
        if (rank >= TIME_HINT_SEQUENCE.size - 1) return TIME_HINT_SEQUENCE.last()
        return TIME_HINT_SEQUENCE[rank + 1]
    }

    private fun isProjectedTimeReference(message: String, start: Int, end: Int): Boolean {
        val prefix = message.substring(maxOf(0, start - 10), start)
        val suffix = message.substring(end, minOf(message.length, end + 4))
        if (Regex("(?:明天|明晚|以后|改天|等|等到|待|待到|要到|将到)$").containsMatchIn(prefix)) return true
        if (prefix.endsWith("一") || prefix.endsWith("整") || prefix.endsWith("整整") ||
            prefix.endsWith("每") || prefix.endsWith("几") || prefix.endsWith("两") || prefix.endsWith("三")
        ) return true
        return Regex("^(?:再|才|要|会)").containsMatchIn(suffix)
    }

    fun inferTimeHint(transcript: List<Map<String, Any?>>, includeCharacterClaims: Boolean = false): String {
        for (item in transcript.takeLast(14).reversed()) {
            val message = item["message"]?.toString()?.trim().orEmpty()
            if (message.isEmpty()) continue
            if (isSceneLevelEntry(item) || includeCharacterClaims) {
                for (token in TIME_HINT_SEQUENCE + TIME_HINT_ALIASES.keys) {
                    val start = message.indexOf(token)
                    if (start >= 0 && !isProjectedTimeReference(message, start, start + token.length)) {
                        return canonicalTimeHint(token)
                    }
                }
            }
            val role = item["role"]?.toString()?.trim().orEmpty()
            if (role in setOf("user", "director") && !isSceneLevelEntry(item)) continue
            for ((cue, target) in TIME_FORWARD_CUES) {
                val start = message.indexOf(cue)
                if (start >= 0 && !isProjectedTimeReference(message, start, start + cue.length)) {
                    return target
                }
            }
        }
        return ""
    }

    fun historyHasTimeDrift(history: List<Map<String, Any?>>, since: String = ""): Boolean {
        val sinceAt = timestampMillis(since)
        val recentMessages = mutableListOf<String>()
        for (item in history.takeLast(8)) {
            if (!isSceneLevelEntry(item)) continue
            if (sinceAt != null) {
                val itemAt = timestampMillis(item["ts"]?.toString().orEmpty()) ?: continue
                if (itemAt <= sinceAt) continue
            }
            val message = item["message"]?.toString()?.trim().orEmpty()
            if (message.isNotEmpty()) recentMessages.add(message)
        }
        return recentMessages.any { message -> TIME_DRIFT_CUES.any { message.contains(it) } }
    }

    fun mergeTimeHint(
        incoming: String,
        base: String,
        history: List<Map<String, Any?>>,
        sceneHint: String = "",
        allowHistoryDrift: Boolean = true,
        historySince: String = "",
    ): String {
        val incomingHint = canonicalTimeHint(incoming)
        val baseHint = canonicalTimeHint(base)
        val sceneBase = canonicalTimeHint(sceneHint)
        val current = baseHint.ifEmpty { sceneBase }
        if (incomingHint.isNotEmpty()) {
            if (current.isEmpty()) return incomingHint
            return if (timeHintRank(incomingHint) >= timeHintRank(current)) incomingHint else current
        }
        if (allowHistoryDrift && current.isNotEmpty() && historyHasTimeDrift(history, since = historySince)) {
            return advanceTimeHint(current)
        }
        return current
    }

    // ------------------------------------------------------------------
    // 在场/离场推断（scene_signals.py）
    // ------------------------------------------------------------------

    fun containsLeaveSignal(text: String, name: String): Boolean {
        val compact = text.replace(Regex("\\s+"), "")
        if (containsStaySignal(compact, name)) return false
        for (token in LEAVE_TOKENS) {
            if ("$name$token" in compact || "$token$name" in compact) return true
            if (Regex(Regex.escape(name) + ".{0,4}" + Regex.escape(token)).containsMatchIn(compact)) return true
            if (Regex(Regex.escape(token) + ".{0,4}" + Regex.escape(name)).containsMatchIn(compact)) return true
        }
        return false
    }

    fun containsReturnSignal(text: String, name: String): Boolean {
        val compact = text.replace(Regex("\\s+"), "")
        for (token in RETURN_TOKENS) {
            if ("$name$token" in compact || "$token$name" in compact) return true
            if (Regex(Regex.escape(name) + ".{0,4}" + Regex.escape(token)).containsMatchIn(compact)) return true
            if (Regex(Regex.escape(token) + ".{0,4}" + Regex.escape(name)).containsMatchIn(compact)) return true
        }
        return false
    }

    fun containsStaySignal(text: String, name: String): Boolean {
        val compact = text.replace(Regex("\\s+"), "")
        val patterns = listOf(
            Regex("只剩[^。！？；，,]*" + Regex.escape(name)),
            Regex("只留下[^。！？；，,]*" + Regex.escape(name)),
            Regex("留在[^。！？；，,]*" + Regex.escape(name)),
            Regex(Regex.escape(name) + "[^。！？；，,]*还在"),
            Regex(Regex.escape(name) + "[^。！？；，,]*仍在"),
        )
        return patterns.any { it.containsMatchIn(compact) }
    }

    fun selfExitSignal(text: String): Boolean {
        val compact = text.replace(Regex("\\s+"), "")
        return SELF_EXIT_SIGNALS.any { compact.contains(it) }
    }

    fun selfReturnSignal(text: String): Boolean {
        val compact = text.replace(Regex("\\s+"), "")
        return SELF_RETURN_SIGNALS.any { compact.contains(it) }
    }

    fun inferDepartedParticipants(participants: List<String>, history: List<Map<String, Any?>>): Set<String> {
        val departed = mutableSetOf<String>()
        for (entry in history.takeLast(16)) {
            val speaker = entry["speaker"]?.toString()?.trim().orEmpty()
            val message = entry["message"]?.toString()?.trim().orEmpty()
            if (message.isEmpty()) continue
            for (name in participants) {
                if (name !in message) continue
                if (speaker !in setOf("旁白", "场景提示") && speaker != name) continue
                if (containsReturnSignal(message, name)) {
                    departed.remove(name)
                    continue
                }
                if (containsLeaveSignal(message, name)) departed.add(name)
            }
            if (speaker in participants && selfExitSignal(message)) departed.add(speaker)
        }
        return departed
    }

    fun inferReturnedParticipants(participants: List<String>, history: List<Map<String, Any?>>): Set<String> {
        val returned = mutableSetOf<String>()
        for (entry in history.takeLast(16)) {
            val speaker = entry["speaker"]?.toString()?.trim().orEmpty()
            val message = entry["message"]?.toString()?.trim().orEmpty()
            if (message.isEmpty()) continue
            for (name in participants) {
                if (name !in message) continue
                if (speaker !in setOf("旁白", "场景提示") && speaker != name) continue
                if (containsReturnSignal(message, name)) returned.add(name)
            }
            if (speaker in participants && selfReturnSignal(message)) returned.add(speaker)
        }
        return returned
    }

    // ------------------------------------------------------------------
    // 事件信号（event_signals.py）
    // ------------------------------------------------------------------

    fun emptyEventSignalsState(): Map<String, Any?> = linkedMapOf(
        "recent" to emptyList<Any?>(),
        "by_type" to emptyMap<String, Any?>(),
        "updated_at" to "",
    )

    fun emptySessionState(): Map<String, Any?> = linkedMapOf(
        "version" to 1,
        "scene" to linkedMapOf(
            "location" to "", "time_hint" to "", "atmosphere_summary" to "",
            "progression_note" to "", "updated_at" to "",
        ),
        "presence" to linkedMapOf(
            "present_participants" to emptyList<Any?>(), "offstage_participants" to emptyList<Any?>(),
            "updated_at" to "",
        ),
        "progression" to linkedMapOf(
            "should_offer_scene_shift" to false, "scene_shift_reason" to "",
            "turns_in_current_scene" to 0, "beat_maturity" to 0,
            "world_tension_summary" to "", "updated_at" to "",
        ),
        "relations" to linkedMapOf("matrix" to emptyMap<String, Any?>(), "delta" to emptyMap<String, Any?>()),
        "characters" to linkedMapOf("snapshots" to emptyMap<String, Any?>()),
        "signals" to emptyEventSignalsState(),
        "memory" to linkedMapOf("summary" to emptyMap<String, Any?>()),
    )

    /** 把 scene progress state 的 Map 转成 JsonObject（仅处理基本类型/列表/嵌套 Map）。 */
    fun stateToJsonObject(state: Map<String, Any?>): JsonObject = buildJsonObject {
        state.forEach { (key, value) ->
            when (value) {
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Double -> put(key, value)
                is Map<*, *> -> put(key, stateToJsonObject(value.mapKeys { it.key.toString() }))
                is List<*> -> put(
                    key,
                    buildJsonArray {
                        value.forEach { item ->
                            when (item) {
                                is Map<*, *> -> add(stateToJsonObject(item.mapKeys { it.key.toString() }))
                                is String -> add(JsonPrimitive(item))
                                is Boolean -> add(JsonPrimitive(item))
                                is Int -> add(JsonPrimitive(item))
                                is Long -> add(JsonPrimitive(item))
                                is Double -> add(JsonPrimitive(item))
                                null -> add(JsonNull)
                                else -> add(JsonPrimitive(item.toString()))
                            }
                        }
                    },
                )
                null -> put(key, JsonNull)
                else -> put(key, value.toString())
            }
        }
    }

    private fun jsonElementToAny(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { (_, child) -> jsonElementToAny(child) }
        is JsonArray -> value.map(::jsonElementToAny)
        is JsonPrimitive -> {
            if (value.isString) {
                value.content
            } else {
                value.content.toBooleanStrictOrNull()
                    ?: value.content.toLongOrNull()
                    ?: value.content.toDoubleOrNull()
                    ?: value.content
            }
        }
    }

    fun sessionState(session: JsonObject): Map<String, Any?> {
        val raw = session["state"]?.jsonObject ?: return emptySessionState()
        // JsonObject implements Map, but its values are still JsonElement instances.
        // Passing those through as Any makes callers use JsonPrimitive.toString(), which
        // includes JSON quotes and escapes. Persisting that value again doubles escaping
        // on every turn. Convert recursively to plain Kotlin values at the boundary.
        return raw.mapValues { (_, value) -> jsonElementToAny(value) }
    }

    fun sessionEventSignals(session: JsonObject): Map<String, Any?> {
        val state = sessionState(session)
        return (state["signals"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyEventSignalsState()
    }

    fun latestEventSignal(eventSignals: Map<String, Any?>, vararg kinds: String): Map<String, Any?> {
        val wanted = kinds.filter { it.isNotBlank() }.toSet()
        if (wanted.isEmpty()) return emptyMap()
        val recent = (eventSignals["recent"] as? List<*>) ?: emptyList<Any?>()
        for (item in recent.reversed()) {
            val event = (item as? Map<*, *>)?.mapKeys { it.key.toString() } ?: continue
            if (event["kind"]?.toString()?.trim() in wanted) return event
        }
        return emptyMap()
    }

    fun mergeEventSignalsState(
        current: Map<String, Any?>,
        incoming: List<Map<String, Any?>>,
        participants: List<String>,
        updatedAt: String,
    ): Map<String, Any?> {
        val recent = ((current["recent"] as? List<*>) ?: emptyList<Any?>())
            .mapNotNull { (it as? Map<*, *>)?.mapKeys { k -> k.toString() } }
        val allowedParticipants = participants.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        fun normalizeEvent(item: Map<String, Any?>): Map<String, Any?> {
            val kind = item["kind"]?.toString()?.trim().orEmpty()
            val shouldInline = item["should_inline"]?.toString()?.toBooleanStrictOrNull() == true
            val scope = item["scope"]?.toString()?.trim()
                ?.ifEmpty { if (shouldInline) "character" else "scene" }.orEmpty()
            var actor = item["actor"]?.toString()?.trim().orEmpty()
            var target = item["target"]?.toString()?.trim().orEmpty()
            val cue = trimSummaryText(item["cue"]?.toString()?.trim().orEmpty(), 160)
            val source = item["source"]?.toString()?.trim().orEmpty().ifEmpty { "runtime" }
            val timeHint = trimSummaryText(item["time_hint"]?.toString()?.trim().orEmpty(), 40)
            val locationHint = trimSummaryText(item["location_hint"]?.toString()?.trim().orEmpty(), 60)
            val ts = item["ts"]?.toString()?.trim().orEmpty().ifEmpty { updatedAt }
            val turnId = item["turn_id"]?.toString()?.trim().orEmpty()
            if (actor.isNotEmpty() && allowedParticipants.isNotEmpty() && actor !in allowedParticipants &&
                actor !in setOf("场景提示", "旁白", "User")
            ) actor = ""
            if (target.isNotEmpty() && allowedParticipants.isNotEmpty() && target !in allowedParticipants) target = ""
            val normalized = linkedMapOf<String, Any?>(
                "kind" to kind,
                "scope" to scope,
                "actor" to actor,
                "target" to target,
                "cue" to cue,
                "source" to source,
                "should_inline" to shouldInline,
                "ts" to ts,
            )
            if (timeHint.isNotEmpty()) normalized["time_hint"] = timeHint
            if (locationHint.isNotEmpty()) normalized["location_hint"] = locationHint
            if (turnId.isNotEmpty()) normalized["turn_id"] = turnId
            return normalized
        }

        val eventMap = LinkedHashMap<String, Map<String, Any?>>()
        for (item in recent + incoming) {
            val normalized = normalizeEvent(item)
            if (normalized["kind"]?.toString().isNullOrEmpty() || normalized["cue"]?.toString().isNullOrEmpty()) continue
            val key = listOf(
                normalized["kind"]?.toString().orEmpty(),
                normalized["actor"]?.toString().orEmpty(),
                normalized["target"]?.toString().orEmpty(),
                normalized["cue"]?.toString().orEmpty(),
            ).joinToString("|")
            eventMap[key] = normalized
        }

        val mergedRecent = eventMap.values
            .sortedBy { it["ts"]?.toString().orEmpty() }
            .takeLast(40)
        val byType = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        for (item in mergedRecent) {
            val kind = item["kind"]?.toString()?.trim().orEmpty()
            if (kind.isEmpty()) continue
            val bucket = byType.getOrPut(kind) { mutableListOf() }
            bucket.add(item)
            if (bucket.size > 8) {
                byType[kind] = bucket.takeLast(8).toMutableList()
            }
        }
        return linkedMapOf(
            "recent" to mergedRecent,
            "by_type" to byType,
            "updated_at" to updatedAt,
        )
    }

    fun buildSessionEventExcerpt(eventSignals: Map<String, Any?>): List<Map<String, Any?>> {
        val recent = (eventSignals["recent"] as? List<*>) ?: emptyList<Any?>()
        val normalized = mutableListOf<Map<String, Any?>>()
        for (item in recent.takeLast(8)) {
            val event = (item as? Map<*, *>)?.mapKeys { it.key.toString() } ?: continue
            val kind = event["kind"]?.toString()?.trim().orEmpty()
            val cue = trimSummaryText(event["cue"]?.toString()?.trim().orEmpty(), 120)
            if (kind.isEmpty() || cue.isEmpty()) continue
            val entry = linkedMapOf<String, Any?>(
                "kind" to kind,
                "scope" to (event["scope"]?.toString()?.trim().orEmpty()),
                "actor" to (event["actor"]?.toString()?.trim().orEmpty()),
                "target" to (event["target"]?.toString()?.trim().orEmpty()),
                "cue" to cue,
                "should_inline" to (event["should_inline"]?.toString()?.toBooleanStrictOrNull() == true),
            )
            event["time_hint"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { entry["time_hint"] = it }
            event["location_hint"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { entry["location_hint"] = it }
            event["turn_id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { entry["turn_id"] = it }
            normalized.add(entry.filterValues { value ->
                value !is String || value.isNotEmpty()
            }.filterValues { value -> value != false && value != emptyList<Any?>() })
        }
        return normalized
    }

    // ------------------------------------------------------------------
    // 场景进度推导（scene_progress.py）
    // ------------------------------------------------------------------

    private fun sceneCardFields(session: JsonObject): Map<String, Any?> {
        val sceneCard = session["scene_card"]?.jsonObject ?: session["scene_profile"]?.jsonObject
            ?: return emptyMap()
        val fields = sceneCard["fields"]?.jsonObject ?: sceneCard
        return fields
    }

    private fun derivePresenceState(
        participants: List<String>,
        history: List<Map<String, Any?>>,
        eventSignals: Map<String, Any?>,
    ): Map<String, Any?> {
        val departed = inferDepartedParticipants(participants, history).toMutableSet()
        val latestExit = latestEventSignal(eventSignals, "cast_exit")
        val latestEnter = latestEventSignal(eventSignals, "cast_enter")
        if (latestExit.isNotEmpty()) {
            val actor = latestExit["actor"]?.toString()?.trim().orEmpty()
            if (actor in participants) departed.add(actor)
        }
        if (latestEnter.isNotEmpty()) {
            val actor = latestEnter["actor"]?.toString()?.trim().orEmpty()
            if (actor in participants) departed.remove(actor)
        }
        val present = participants.filter { it !in departed }
        val resolvedPresent = if (present.isEmpty() && participants.isNotEmpty()) listOf(participants.first()) else present
        return linkedMapOf(
            "present_participants" to resolvedPresent,
            "offstage_participants" to participants.filter { it !in resolvedPresent },
        )
    }

    private fun inferAtmosphereSummary(transcript: List<Map<String, Any?>>): String {
        val recentMessages = transcript.takeLast(8)
            .mapNotNull { item -> item["message"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
        if (recentMessages.isEmpty()) return ""
        val joined = recentMessages.joinToString(" ")
        for (token in ATMOSPHERE_TOKENS) {
            if (token in joined) return trimSummaryText(token, 40)
        }
        for (message in recentMessages.reversed()) {
            val trimmed = trimSummaryText(message, 40)
            if (trimmed.isNotEmpty()) return trimmed
        }
        return ""
    }

    private fun deriveSceneFrameState(
        session: JsonObject,
        transcript: List<Map<String, Any?>>,
        sceneCard: Map<String, Any?>,
        prior: Map<String, Any?>,
        eventSignals: Map<String, Any?>,
    ): Map<String, Any?> {
        val latestTimeEvent = latestEventSignal(eventSignals, "time_change")
        val latestSceneEvent = latestEventSignal(eventSignals, "scene_transition")
        val history = transcript.map { it }
        val timeHint = mergeTimeHint(
            incoming = latestTimeEvent["time_hint"]?.toString()?.trim()
                ?.ifEmpty { inferTimeHint(transcript) }.orEmpty(),
            base = prior["time_hint"]?.toString()?.trim().orEmpty(),
            history = history,
            sceneHint = sceneCard["time_hint"]?.toString()?.trim().orEmpty(),
            historySince = prior["updated_at"]?.toString()?.trim().orEmpty(),
        )
        val location = latestSceneEvent["location_hint"]?.toString()?.trim()
            ?.ifEmpty { prior["location"]?.toString()?.trim().orEmpty() }
            ?.ifEmpty { sceneCard["location"]?.toString()?.trim().orEmpty() }.orEmpty()
        val latestAtmosphereEvent = latestEventSignal(eventSignals, "atmosphere_shift")
        val atmosphere = listOf(
            latestAtmosphereEvent["cue"]?.toString()?.trim()?.let { trimSummaryText(it, 80) }.orEmpty(),
            inferAtmosphereSummary(transcript),
            prior["atmosphere_summary"]?.toString()?.trim()?.let { trimSummaryText(it, 80) }.orEmpty(),
            sceneCard["atmosphere"]?.toString()?.trim()?.let { trimSummaryText(it, 80) }.orEmpty(),
        ).firstOrNull { it.isNotEmpty() }.orEmpty()
        return linkedMapOf(
            "time_hint" to timeHint,
            "location" to location,
            "atmosphere_summary" to atmosphere,
        )
    }

    private fun countCurrentSceneTurns(session: JsonObject, history: List<Map<String, Any?>>): Int {
        val sceneHistory = session["scene_history"]?.jsonArray ?: JsonArray(emptyList())
        val latestSceneTs = sceneHistory.lastOrNull()?.jsonObject?.get("switched_at")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (latestSceneTs.isNotEmpty()) {
            return history.count { item ->
                val ts = item["timestamp"]?.toString()?.trim().orEmpty()
                val message = item["message"]?.toString()?.trim().orEmpty()
                ts >= latestSceneTs && message.isNotEmpty()
            }
        }
        return history.takeLast(12).count { item ->
            item["message"]?.toString()?.trim().isNullOrEmpty().not()
        }
    }

    private fun deriveTransitionPressureReason(
        presenceState: Map<String, Any?>,
        sceneFrame: Map<String, Any?>,
        sceneCard: Map<String, Any?>,
        prior: Map<String, Any?>,
        eventSignals: Map<String, Any?>,
    ): String {
        val present = (presenceState["present_participants"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val offstage = (presenceState["offstage_participants"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val latestExit = latestEventSignal(eventSignals, "cast_exit")
        val actor = latestExit["actor"]?.toString()?.trim().orEmpty()
        if (actor.isNotEmpty() && actor in offstage) {
            if (present.size <= 1 && present.isNotEmpty()) return "${actor}已经离场，场上只剩${present.first()}，适合顺势切到下一幕。"
            return "${actor}已经离场，在场关系重新收束，适合顺势转下一拍。"
        }
        val latestSceneEvent = latestEventSignal(eventSignals, "scene_transition")
        val location = sceneFrame["location"]?.toString()?.trim().orEmpty()
        if (latestSceneEvent.isNotEmpty() && location.isNotEmpty()) {
            val priorLocation = prior["location"]?.toString()?.trim().orEmpty()
            val sceneLocation = sceneCard["location"]?.toString()?.trim().orEmpty()
            if (location != priorLocation && location != sceneLocation) {
                return "地点已经转到$location，适合顺势接下一幕。"
            }
        }
        return ""
    }

    private fun estimateSceneMaturity(
        turnsInCurrentScene: Int,
        transcript: List<Map<String, Any?>>,
        sceneCard: Map<String, Any?>,
        presenceState: Map<String, Any?>,
        sceneFrame: Map<String, Any?>,
        latestBeatEvent: Map<String, Any?>,
        prior: Map<String, Any?>,
    ): Int {
        var score = minOf(60, maxOf(0, turnsInCurrentScene * 10))
        if (latestBeatEvent.isNotEmpty()) score += 25
        val timeHint = sceneFrame["time_hint"]?.toString()?.trim().orEmpty()
        if (timeHint.isNotEmpty() && timeHint != sceneCard["time_hint"]?.toString()?.trim().orEmpty()) score += 10
        val location = sceneFrame["location"]?.toString()?.trim().orEmpty()
        if (location.isNotEmpty() && location != sceneCard["location"]?.toString()?.trim().orEmpty()) score += 10
        if (((presenceState["offstage_participants"] as? List<*>) ?: emptyList<Any?>()).isNotEmpty()) score += 6
        if (sceneFrame["atmosphere_summary"]?.toString()?.trim().isNullOrEmpty().not()) score += 4
        val previousMaturity = (prior["beat_maturity"] as? Number)?.toInt() ?: 0
        if (previousMaturity > 0) score = maxOf(score, minOf(100, previousMaturity - 8))
        if (transcript.size >= 6) score += 6
        return maxOf(0, minOf(100, score))
    }

    private fun deriveWorldTensionSummary(
        session: JsonObject,
        transcript: List<Map<String, Any?>>,
        sceneFrame: Map<String, Any?>,
        eventSignals: Map<String, Any?>,
    ): String {
        val latestAtmosphereEvent = latestEventSignal(eventSignals, "atmosphere_shift")
        val latestRelationEvent = latestEventSignal(eventSignals, "relationship_shift")
        val latestSceneEvent = latestEventSignal(eventSignals, "scene_transition", "environment_change", "time_change")
        for (candidate in listOf(latestAtmosphereEvent, latestRelationEvent, latestSceneEvent)) {
            val cue = trimSummaryText(candidate["cue"]?.toString()?.trim().orEmpty(), 88)
            if (cue.isNotEmpty()) return cue
        }
        val state = sessionState(session)
        val relationDelta = ((state["relations"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.get("delta") as? Map<*, *>)
            ?.mapKeys { it.key.toString() } ?: emptyMap()
        val first = relationDelta.entries.firstOrNull()
        if (first != null) {
            val pairKey = first.key
            val delta = (first.value as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
            val metrics = mutableListOf<String>()
            for ((field, label) in listOf("trust" to "信任", "affection" to "好感", "hostility" to "敌意", "ambiguity" to "摇摆")) {
                val amount = (delta[field] as? Number)?.toInt() ?: 0
                if (amount != 0) metrics.add("$label${if (amount > 0) "+" else ""}$amount")
            }
            if (metrics.isNotEmpty()) return trimSummaryText("$pairKey 当前仍在变化：${metrics.joinToString("、")}", 88)
        }
        val atmosphere = sceneFrame["atmosphere_summary"]?.toString()?.trim().orEmpty()
        if (atmosphere.isNotEmpty()) return trimSummaryText("这一拍的气氛是：$atmosphere", 88)
        for (item in transcript.takeLast(8).reversed()) {
            val role = item["role"]?.toString()?.trim().orEmpty()
            val message = trimSummaryText(item["message"]?.toString()?.trim().orEmpty(), 88)
            if (role in setOf("scene", "director") && message.isNotEmpty()) return message
        }
        return ""
    }

    private fun deriveProgressionState(
        session: JsonObject,
        transcript: List<Map<String, Any?>>,
        sceneCard: Map<String, Any?>,
        prior: Map<String, Any?>,
        presenceState: Map<String, Any?>,
        sceneFrame: Map<String, Any?>,
        eventSignals: Map<String, Any?>,
    ): Map<String, Any?> {
        val latestBeatEvent = latestEventSignal(eventSignals, "beat_complete")
        val turnsInCurrentScene = countCurrentSceneTurns(session, transcript)
        val beatMaturity = estimateSceneMaturity(
            turnsInCurrentScene = turnsInCurrentScene,
            transcript = transcript,
            sceneCard = sceneCard,
            presenceState = presenceState,
            sceneFrame = sceneFrame,
            latestBeatEvent = latestBeatEvent,
            prior = prior,
        )
        var shouldOfferSceneShift = false
        var sceneShiftReason = ""
        if (sceneCard.isNotEmpty() && beatMaturity >= 72) {
            shouldOfferSceneShift = true
            sceneShiftReason = "这一幕已经接了好几拍，可以顺势换到下一幕。"
        }
        if (latestBeatEvent.isNotEmpty()) {
            shouldOfferSceneShift = true
            sceneShiftReason = latestBeatEvent["cue"]?.toString()?.trim().orEmpty().ifEmpty { sceneShiftReason }
        }
        val initialTime = sceneCard["time_hint"]?.toString()?.trim().orEmpty()
        val timeHint = sceneFrame["time_hint"]?.toString()?.trim().orEmpty()
        if (timeHint.isNotEmpty() && initialTime.isNotEmpty() && timeHint != initialTime && beatMaturity >= 55) {
            shouldOfferSceneShift = true
            if (sceneShiftReason.isEmpty()) sceneShiftReason = "时间已经自然推到$timeHint，适合顺势转下一拍。"
        }
        val eventPressureReason = deriveTransitionPressureReason(
            presenceState = presenceState,
            sceneFrame = sceneFrame,
            sceneCard = sceneCard,
            prior = prior,
            eventSignals = eventSignals,
        )
        if (eventPressureReason.isNotEmpty() && beatMaturity >= 42) {
            shouldOfferSceneShift = true
            if (sceneShiftReason.isEmpty()) sceneShiftReason = eventPressureReason
        }
        return linkedMapOf(
            "should_offer_scene_shift" to shouldOfferSceneShift,
            "scene_shift_reason" to sceneShiftReason,
            "turns_in_current_scene" to turnsInCurrentScene,
            "beat_maturity" to beatMaturity,
            "world_tension_summary" to deriveWorldTensionSummary(session, transcript, sceneFrame, eventSignals),
        )
    }

    /**
     * 推导场景进度状态（对齐 Python derive_scene_progress_state）。
     * 返回可直接写入 session["state"] 的完整状态。
     */
    fun deriveSceneProgressState(
        session: JsonObject,
        transcript: List<Map<String, Any?>>,
        updatedAt: String = "",
    ): Map<String, Any?> {
        // 状态推导只依赖近期窗口（takeLast ≤16）与上一轮 state；截取最近 512 条，
        // 避免长会话每轮全量扫描（O(1)/轮）。单场景超过 512 轮时 turns_in_current_scene
        // 会封顶在窗口内统计，实际会话几乎不会触及。
        val recentTranscript = transcript.takeLast(512)
        val state = sessionState(session)
        val priorScene = (state["scene"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val priorPresence = (state["presence"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val priorProgression = (state["progression"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val prior = linkedMapOf<String, Any?>()
        prior.putAll(priorScene)
        prior.putAll(priorPresence)
        prior.putAll(priorProgression)
        val participants = (session["participants"]?.jsonArray ?: JsonArray(emptyList()))
            .mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }.filter { it.isNotEmpty() }
        val sceneCard = sceneCardFields(session)
        val eventSignals = sessionEventSignals(session)

        val presenceState = derivePresenceState(participants, recentTranscript, eventSignals)
        val sceneFrame = deriveSceneFrameState(session, recentTranscript, sceneCard, prior, eventSignals)
        val progressionState = deriveProgressionState(
            session, recentTranscript, sceneCard, prior, presenceState, sceneFrame, eventSignals,
        )
        val progressionBits = mutableListOf<String>()
        sceneFrame["location"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { progressionBits.add("地点：$it") }
        sceneFrame["time_hint"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { progressionBits.add("时间：$it") }
        sceneFrame["atmosphere_summary"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { progressionBits.add("氛围：$it") }
        ((presenceState["present_participants"] as? List<*>) ?: emptyList<Any?>()).mapNotNull { it?.toString() }
            .take(4).takeIf { it.isNotEmpty() }?.let { progressionBits.add("在场：${it.joinToString("、")}") }
        ((presenceState["offstage_participants"] as? List<*>) ?: emptyList<Any?>()).mapNotNull { it?.toString() }
            .take(3).takeIf { it.isNotEmpty() }?.let { progressionBits.add("离场：${it.joinToString("、")}") }
        progressionBits.add("成熟度：${(progressionState["beat_maturity"] as? Number)?.toInt() ?: 0}")

        val now = updatedAt.ifEmpty { nowIsoString() }
        val scene = linkedMapOf<String, Any?>(
            "location" to (sceneFrame["location"]?.toString()?.trim().orEmpty()),
            "time_hint" to (sceneFrame["time_hint"]?.toString()?.trim().orEmpty()),
            "atmosphere_summary" to (sceneFrame["atmosphere_summary"]?.toString()?.trim().orEmpty()),
            "progression_note" to progressionBits.filter { it.isNotEmpty() }.joinToString("；"),
            "updated_at" to now,
        )
        val presence = linkedMapOf<String, Any?>(
            "present_participants" to ((presenceState["present_participants"] as? List<*>) ?: emptyList<Any?>()),
            "offstage_participants" to ((presenceState["offstage_participants"] as? List<*>) ?: emptyList<Any?>()),
            "updated_at" to now,
        )
        val progression = linkedMapOf<String, Any?>(
            "should_offer_scene_shift" to (progressionState["should_offer_scene_shift"] ?: false),
            "scene_shift_reason" to (progressionState["scene_shift_reason"]?.toString()?.trim().orEmpty()),
            "turns_in_current_scene" to ((progressionState["turns_in_current_scene"] as? Number)?.toInt() ?: 0),
            "beat_maturity" to ((progressionState["beat_maturity"] as? Number)?.toInt() ?: 0),
            "world_tension_summary" to (progressionState["world_tension_summary"]?.toString()?.trim().orEmpty()),
            "updated_at" to now,
        )
        return linkedMapOf(
            "version" to 1,
            "scene" to scene,
            "presence" to presence,
            "progression" to progression,
            "relations" to (state["relations"] ?: linkedMapOf("matrix" to emptyMap<String, Any?>(), "delta" to emptyMap<String, Any?>())),
            "characters" to (state["characters"] ?: linkedMapOf("snapshots" to emptyMap<String, Any?>())),
            "signals" to eventSignals,
            "memory" to (state["memory"] ?: linkedMapOf("summary" to emptyMap<String, Any?>())),
        )
    }

    /**
     * 场景卡切换后的状态推导（对齐 Python service.py switch_scene_card）：
     * 写入 scene_transition/time_change/atmosphere_shift 事件信号，
     * 覆盖 location/time/atmosphere，并重置 progression。
     */
    fun deriveAfterSceneSwitch(
        session: JsonObject,
        sceneProfile: Map<String, Any?>,
        transitionMessage: String,
        switchedAt: String,
    ): Map<String, Any?> {
        val participants = (session["participants"]?.jsonArray ?: JsonArray(emptyList()))
            .mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }.filter { it.isNotEmpty() }
        val existingSignals = sessionEventSignals(session)
        val transitionEvents = mutableListOf<Map<String, Any?>>()
        val sceneLocation = sceneProfile["location"]?.toString()?.trim().orEmpty()
        val sceneTime = sceneProfile["time_hint"]?.toString()?.trim().orEmpty()
        val sceneAtmosphere = sceneProfile["atmosphere"]?.toString()?.trim().orEmpty()
        val sceneCue = transitionMessage.ifEmpty {
            listOfNotNull(
                sceneProfile["title"]?.toString()?.trim(),
                sceneLocation,
                sceneAtmosphere,
            ).joinToString(" / ").let { if (it.isNotEmpty()) "场景转到：$it。" else "场景发生了变化。" }
        }
        transitionEvents.add(
            linkedMapOf(
                "kind" to "scene_transition",
                "scope" to "scene",
                "actor" to "场景提示",
                "cue" to sceneCue,
                "source" to "scene_card_switch",
                "location_hint" to sceneLocation,
                "ts" to switchedAt,
            ).filterValues { it.isNotEmpty() },
        )
        if (sceneTime.isNotEmpty()) {
            transitionEvents.add(
                linkedMapOf(
                    "kind" to "time_change",
                    "scope" to "scene",
                    "actor" to "场景提示",
                    "cue" to "新场景时间：$sceneTime",
                    "source" to "scene_card_switch",
                    "time_hint" to sceneTime,
                    "ts" to switchedAt,
                ),
            )
        }
        if (sceneAtmosphere.isNotEmpty()) {
            transitionEvents.add(
                linkedMapOf(
                    "kind" to "atmosphere_shift",
                    "scope" to "scene",
                    "actor" to "场景提示",
                    "cue" to sceneAtmosphere,
                    "source" to "scene_card_switch",
                    "ts" to switchedAt,
                ),
            )
        }
        val mergedSignals = mergeEventSignalsState(existingSignals, transitionEvents, participants, switchedAt)
        val base = deriveSceneProgressState(session, transcriptOf(session), updatedAt = switchedAt)
        val scene = ((base["scene"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.toMutableMap() ?: mutableMapOf())
        if (sceneLocation.isNotEmpty()) scene["location"] = sceneLocation
        if (sceneTime.isNotEmpty()) scene["time_hint"] = sceneTime
        if (sceneAtmosphere.isNotEmpty()) scene["atmosphere_summary"] = sceneAtmosphere
        scene["progression_note"] = ""
        scene["updated_at"] = switchedAt
        val progression = linkedMapOf(
            "should_offer_scene_shift" to false,
            "scene_shift_reason" to "",
            "turns_in_current_scene" to 0,
            "beat_maturity" to 0,
            "world_tension_summary" to "",
            "updated_at" to switchedAt,
        )
        return linkedMapOf(
            "version" to 1,
            "scene" to scene,
            "presence" to (base["presence"] ?: emptyMap<String, Any?>()),
            "progression" to progression,
            "relations" to (base["relations"] ?: emptyMap<String, Any?>()),
            "characters" to (base["characters"] ?: emptyMap<String, Any?>()),
            "signals" to mergedSignals,
            "memory" to (base["memory"] ?: emptyMap<String, Any?>()),
        )
    }

    private fun transcriptOf(session: JsonObject): List<Map<String, Any?>> {
        return (session["transcript"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { raw ->
            runCatching {
                raw.jsonObject.mapKeys { it.key }.mapValues { (_, value) ->
                    when (value) {
                        is JsonObject -> value.mapKeys { it.key }
                        else -> value.jsonPrimitive.contentOrNull
                    }
                }
            }.getOrNull()
        }
    }
}

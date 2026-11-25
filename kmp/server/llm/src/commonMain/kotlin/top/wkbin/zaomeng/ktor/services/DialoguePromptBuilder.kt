package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.WorldBookInjectionPosition
import top.wkbin.zaomeng.data.api.WORLD_BOOK_CONTENT_CHAR_LIMIT
import top.wkbin.zaomeng.data.api.WorldBookInjectionRole
import top.wkbin.zaomeng.data.api.WorldBookMatchDto
import top.wkbin.zaomeng.data.api.AuthorNoteDto
import top.wkbin.zaomeng.data.api.AuthorNotePosition
import top.wkbin.zaomeng.data.api.expandSillyTavernNames
import top.wkbin.zaomeng.data.api.expandSillyTavernOutlets
import top.wkbin.zaomeng.data.api.sillyTavernOutletCardFields
import top.wkbin.zaomeng.data.api.sillyTavernExampleBlocks

/**
 * 对话 LLM 消息构建（迁移自 Python src/web/chat/helpers.py）。
 *
 * 输入为结构化 payload（Map<String, Any?>，由 DialoguePayloadBuilder 组装），
 * 输出为 LLM 消息列表（system/user），与 Python 逐字段对齐。
 */
class DialoguePromptBuilder(
    private val promptLoader: PromptLoader,
) {
    private val json = Json

    // ------------------------------------------------------------------
    // JSON <-> Map 工具
    // ------------------------------------------------------------------

    fun jsonToMap(element: JsonElement): Any? = when (element) {
        is JsonObject -> element.mapValues { jsonToMap(it.value) }
        is JsonArray -> element.map { jsonToMap(it) }
        is JsonPrimitive -> if (element.isString) element.content else element.contentOrNull ?: element.toString()
        is JsonNull -> null
    }

    fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toString())
        is Map<*, *> -> buildJsonObject {
            value.forEach { (key, item) -> put(key.toString(), toJsonElement(item)) }
        }
        is List<*> -> buildJsonArray { value.forEach { add(toJsonElement(it)) } }
        else -> JsonPrimitive(value.toString())
    }

    fun compactJson(value: Any?): String = json.encodeToString(JsonElement.serializer(), toJsonElement(value))

    // ------------------------------------------------------------------
    // 基础工具（helpers.py _trim_text / _has_meaningful_value / _normalize_short_list）
    // ------------------------------------------------------------------

    private fun trimText(text: Any?, limit: Int): String {
        val cleaned = text?.toString()?.trim().orEmpty()
        if (cleaned.length <= limit) return cleaned
        return cleaned.take(maxOf(1, limit - 1)).trimEnd() + "…"
    }

    private fun hasMeaningfulValue(value: Any?): Boolean {
        if (value is List<*>) return value.isNotEmpty()
        return value?.toString()?.trim().isNullOrEmpty().not()
    }

    private fun normalizeShortList(value: Any?): Any {
        if (value is List<*>) {
            val cleaned = value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
            return cleaned.take(4)
        }
        val text = value?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return ""
        val parts = text.replace("；", ";").split(";").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isNotEmpty()) parts.take(4) else text
    }

    // ------------------------------------------------------------------
    // persona compact（helpers.py _compact_persona_context 系列）
    // ------------------------------------------------------------------

    private fun compactPersonaContext(item: Any?): Map<String, Any?> {
        val raw = item as? Map<*, *> ?: emptyMap<String, Any?>()
        val map = raw.mapKeys { it.key.toString() }
        val preview = (map["preview"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val profile = (map["profile"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val snapshot = (map["session_snapshot"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()

        val compactPreview = mapOf(
            "display_name" to (preview["display_name"]?.toString()?.trim().orEmpty()),
            "core_identity" to (preview["core_identity"]?.toString()?.trim().orEmpty()),
            "speech_style" to (preview["speech_style"]?.toString()?.trim().orEmpty()),
            "appearance_feature" to trimText(preview["appearance_feature"], 80),
        ).filterValues { hasMeaningfulValue(it) }

        val compactProfile = mapOf(
            "core_identity" to (profile["core_identity"]?.toString()?.trim().orEmpty()),
            "story_role" to (profile["story_role"]?.toString()?.trim().orEmpty()),
            "gender" to (profile["gender"]?.toString()?.trim().orEmpty()),
            "age_stage" to (profile["age_stage"]?.toString()?.trim().orEmpty()),
            "appearance_feature" to trimText(profile["appearance_feature"], 100),
            "habit_action" to trimText(profile["habit_action"], 80),
            "speech_style" to (profile["speech_style"]?.toString()?.trim().orEmpty()),
            "temperament_type" to (profile["temperament_type"]?.toString()?.trim().orEmpty()),
            "stress_response" to (profile["stress_response"]?.toString()?.trim().orEmpty()),
            "key_bonds" to normalizeShortList(profile["key_bonds"]),
            "preference_like" to normalizeShortList(profile["preference_like"]),
            "dislike_hate" to normalizeShortList(profile["dislike_hate"]),
        ).filterValues { hasMeaningfulValue(it) }

        val compactSnapshot = mapOf(
            "mood" to (snapshot["mood"]?.toString()?.trim().orEmpty()),
            "interaction_state" to (snapshot["interaction_state"]?.toString()?.trim().orEmpty()),
            "focus" to (snapshot["focus"]?.toString()?.trim().orEmpty()),
            "last_target" to (snapshot["last_target"]?.toString()?.trim().orEmpty()),
            "last_event" to trimText(snapshot["last_event"], 80),
        ).filterValues { hasMeaningfulValue(it) }

        return mapOf(
            "name" to (map["name"]?.toString()?.trim().orEmpty()),
            "preview" to compactPreview,
            "profile" to compactProfile,
            "session_snapshot" to compactSnapshot,
        )
    }

    private fun compactStaticPersonaContext(item: Any?): Map<String, Any?> {
        val compact = compactPersonaContext(item)
        val profile = (compact["profile"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.toMutableMap() ?: mutableMapOf()
        profile.remove("preference_like")
        profile.remove("dislike_hate")
        return mapOf(
            "name" to (compact["name"]?.toString()?.trim().orEmpty()),
            "preview" to (compact["preview"] ?: emptyMap<Any?, Any?>()),
            "profile" to profile,
        )
    }

    private fun compactActivePersonaState(item: Any?): Map<String, Any?> {
        val compact = compactPersonaContext(item)
        val profile = (compact["profile"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val details = mapOf(
            "preference_like" to profile["preference_like"],
            "dislike_hate" to profile["dislike_hate"],
        ).filterValues { hasMeaningfulValue(it) }
        val result = linkedMapOf<String, Any?>(
            "name" to (compact["name"]?.toString()?.trim().orEmpty()),
            "profile_details" to details,
            "session_snapshot" to (compact["session_snapshot"] ?: emptyMap<Any?, Any?>()),
        )
        return result.filterValues { hasMeaningfulValue(it) }
    }

    private fun compactUserPersona(persona: Any?): Map<String, Any?> {
        val raw = persona as? Map<*, *> ?: emptyMap<String, Any?>()
        val map = raw.mapKeys { it.key.toString() }
        val profile = (map["profile"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val compactProfile = mapOf(
            "display_name" to (profile["display_name"]?.toString()?.trim().orEmpty()),
            "scene_identity" to (profile["scene_identity"]?.toString()?.trim().orEmpty()),
            "interaction_style" to (profile["interaction_style"]?.toString()?.trim().orEmpty()),
            "core_identity" to (profile["core_identity"]?.toString()?.trim().orEmpty()),
            "story_role" to (profile["story_role"]?.toString()?.trim().orEmpty()),
            "gender" to (profile["gender"]?.toString()?.trim().orEmpty()),
            "age_stage" to (profile["age_stage"]?.toString()?.trim().orEmpty()),
            "appearance_feature" to trimText(profile["appearance_feature"], 100),
            "habit_action" to trimText(profile["habit_action"], 80),
            "soul_goal" to (profile["soul_goal"]?.toString()?.trim().orEmpty()),
            "speech_style" to (profile["speech_style"]?.toString()?.trim().orEmpty()),
            "worldview" to trimText(profile["worldview"], 120),
            "belief_anchor" to trimText(profile["belief_anchor"], 120),
            "stress_response" to trimText(profile["stress_response"], 120),
            "key_bonds" to normalizeShortList(profile["key_bonds"]),
            "preference_like" to normalizeShortList(profile["preference_like"]),
            "dislike_hate" to normalizeShortList(profile["dislike_hate"]),
            "preferred_moves" to normalizeShortList(profile["preferred_moves"]),
            "goal" to (profile["goal"]?.toString()?.trim().orEmpty()),
        ).filterValues { hasMeaningfulValue(it) }

        val compactPersona = map.toMutableMap()
        compactPersona["profile"] = compactProfile
        val sceneCard = (map["scene_card"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        compactPersona["scene_card"] = mapOf(
            "title" to (sceneCard["title"]?.toString()?.trim().orEmpty()),
            "location" to (sceneCard["location"]?.toString()?.trim().orEmpty()),
            "atmosphere" to (sceneCard["atmosphere"]?.toString()?.trim().orEmpty()),
            "opening_situation" to trimText(sceneCard["opening_situation"], 140),
            "public_goal" to trimText(sceneCard["public_goal"], 140),
            "hidden_tension" to trimText(sceneCard["hidden_tension"], 140),
            "scene_drive" to trimText(sceneCard["scene_drive"], 140),
            "expected_rhythm" to (sceneCard["expected_rhythm"]?.toString()?.trim().orEmpty()),
        ).filterValues { hasMeaningfulValue(it) }
        return compactPersona
    }

    // ------------------------------------------------------------------
    // association compact（helpers.py _compact_association_*）
    // ------------------------------------------------------------------

    private fun compactAssociationHistory(history: List<*>): List<Map<String, String>> {
        val compact = mutableListOf<Map<String, String>>()
        for (rawItem in history.takeLast(4)) {
            val item = rawItem as? Map<*, *> ?: continue
            val map = item.mapKeys { it.key.toString() }
            val entry = mapOf(
                "speaker" to (map["speaker"]?.toString()?.trim().orEmpty()),
                "role" to (map["role"]?.toString()?.trim().orEmpty()),
                "message" to trimText(map["message"], 160),
            ).filterValues { it.isNotEmpty() }
            if (entry["message"].isNullOrEmpty().not()) {
                compact.add(entry)
            }
        }
        return compact
    }

    private fun compactAssociationSceneCard(sceneCard: Map<String, Any?>): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        for (key in listOf(
            "title", "location", "time", "time_hint", "atmosphere",
            "opening_situation", "public_goal", "hidden_tension", "scene_drive", "expected_rhythm",
        )) {
            val value = sceneCard[key]
            if (!hasMeaningfulValue(value)) continue
            result[key] = trimText(value, 140)
        }
        return result
    }

    private fun compactAssociationSceneProgress(sceneProgress: Map<String, Any?>): Map<String, Any?> {
        val compact = linkedMapOf<String, Any?>()
        for (key in listOf(
            "present_participants", "offstage_participants", "time_hint", "location",
            "atmosphere_summary", "progression_note", "beat_maturity", "world_tension_summary",
            "should_offer_scene_shift", "scene_shift_reason", "next_hint",
        )) {
            val value = sceneProgress[key]
            if (!hasMeaningfulValue(value)) continue
            compact[key] = when (value) {
                is List<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                    .take(6).map { trimText(it, 80) }
                is String -> trimText(value, 160)
                else -> value
            }
        }
        return compact
    }

    private fun compactMemoryContext(
        memoryContext: Map<String, Any?>,
        omitPositionedLore: Boolean = false,
    ): Map<String, Any?> {
        val sessionSummary = (memoryContext["session_summary"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val archivedSummary = (memoryContext["archived_summary"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val retrievedMemories = memoryContext["retrieved_memories"] as? List<*> ?: emptyList<Any?>()
        val sceneProgress = (memoryContext["scene_progress"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val relationDelta = (memoryContext["relation_delta"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val characterSnapshots = (memoryContext["character_snapshots"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val eventSignals = memoryContext["event_signals"] as? List<*> ?: emptyList<Any?>()
        val controlledMemories = memoryContext["controlled_memories"] as? List<*> ?: emptyList<Any?>()
        val worldFacts = memoryContext["world_facts"] as? List<*> ?: emptyList<Any?>()

        val compactArchived = mapOf(
            "summary" to trimText(archivedSummary["summary"], 180),
            "key_points" to ((archivedSummary["key_points"] as? List<*>) ?: emptyList<Any?>())
                .mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }.take(3).map { trimText(it, 80) },
            "compressed_turns" to (archivedSummary["compressed_turns"] ?: 0),
        ).filterValues { hasMeaningfulValue(it) }

        val compactHits = mutableListOf<Map<String, Any?>>()
        for (item in retrievedMemories.take(2)) {
            val map = (item as? Map<*, *>)?.mapKeys { it.key.toString() } ?: continue
            val hit = mapOf(
                "text" to trimText(map["text"], 100),
                "speaker" to (map["speaker"]?.toString()?.trim().orEmpty()),
                "target" to (map["target"]?.toString()?.trim().orEmpty()),
                "kind" to (map["kind"]?.toString()?.trim().orEmpty()),
            ).filterValues { hasMeaningfulValue(it) }
            if (hit.isNotEmpty()) compactHits.add(hit)
        }

        val compactWorldFacts = mutableListOf<Map<String, Any?>>()
        val sortedFacts = worldFacts.filterIsInstance<Map<*, *>>()
            .sortedBy { fact ->
                val locked = (fact["locked"] as? Boolean) == true ||
                    fact["locked"]?.toString() == "true"
                if (locked) 0 else 1
            }
        var ordinaryFactCount = 0
        var exemptFactCount = 0
        var remainingLoreChars = WORLD_BOOK_CONTENT_CHAR_LIMIT
        for (item in sortedFacts) {
            if (item["ignore_budget"] == true) {
                if (exemptFactCount++ >= DialoguePromptRules.WORLD_FACT_LIMIT) continue
            } else if (ordinaryFactCount++ >= DialoguePromptRules.WORLD_FACT_LIMIT) {
                continue
            }
            if (omitPositionedLore && item["world_book_position"] is WorldBookInjectionPosition) continue
            val map = item.mapKeys { it.key.toString() }
            val summary = if (map["category"] == "sillytavern_lore") {
                val lore = (map["summary"] as? String).orEmpty()
                if (lore.length > remainingLoreChars) continue
                remainingLoreChars -= lore.length
                lore
            } else trimText(map["summary"], 240)
            val fact = mapOf(
                "fact_id" to (map["fact_id"]?.toString()?.trim().orEmpty()),
                "category" to (map["category"]?.toString()?.trim().orEmpty()),
                "summary" to summary,
                "characters" to ((map["characters"] as? List<*>) ?: emptyList<Any?>())
                    .mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }.take(12).map { trimText(it, 80) },
                "location" to trimText(map["location"], 100),
                "time_hint" to trimText(map["time_hint"], 80),
                "locked" to ((map["locked"] as? Boolean) == true || map["locked"]?.toString() == "true"),
            ).filterValues { hasMeaningfulValue(it) || it is Boolean }
            if (fact["summary"]?.toString().isNullOrEmpty().not()) compactWorldFacts.add(fact)
        }

        val compactSceneProgress = linkedMapOf<String, Any?>()
        sceneProgress.forEach { (innerKey, innerValue) ->
            if (!hasMeaningfulValue(innerValue)) return@forEach
            compactSceneProgress[innerKey] = if (innerValue is List<*>) innerValue.take(6) else innerValue
        }

        val compactRelationDelta = linkedMapOf<String, Any?>()
        relationDelta.entries.take(3).forEach { (pairKey, delta) ->
            if (pairKey.isBlank()) return@forEach
            val deltaMap = (delta as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
            val filtered = deltaMap.filterValues { metricValue ->
                metricValue.toString().isNotEmpty() && metricValue.toString() != "[]" &&
                    metricValue.toString() != "0" && metricValue != null
            }
            if (filtered.isNotEmpty()) compactRelationDelta[pairKey] = filtered
        }

        val compactSnapshots = linkedMapOf<String, Any?>()
        characterSnapshots.entries.take(4).forEach { (name, snapshot) ->
            if (name.isBlank()) return@forEach
            val snapMap = (snapshot as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
            val filtered = snapMap.filterValues { hasMeaningfulValue(it) }
                .mapValues { trimText(it.value, 80) }
            if (filtered.isNotEmpty()) compactSnapshots[name] = filtered
        }

        val compactSignals = mutableListOf<Map<String, Any?>>()
        for (item in eventSignals.takeLast(6)) {
            val map = (item as? Map<*, *>)?.mapKeys { it.key.toString() } ?: continue
            if (map["kind"]?.toString().isNullOrEmpty()) continue
            val filtered = map.filterValues { value ->
                value.toString().isNotEmpty() && value.toString() != "[]" && value != null && value != false
            }.mapValues { (key, value) ->
                if (value is String) trimText(value, 80) else value
            }
            if (filtered.isNotEmpty()) compactSignals.add(filtered)
        }

        val compactControlled = mutableListOf<Map<String, Any?>>()
        for (item in controlledMemories.take(20)) {
            val map = (item as? Map<*, *>)?.mapKeys { it.key.toString() } ?: continue
            val text = map["text"]?.toString()?.trim().orEmpty()
            if (text.isEmpty()) continue
            compactControlled.add(
                mapOf(
                    "memory_id" to (map["memory_id"]?.toString()?.trim().orEmpty()),
                    "text" to trimText(text, 500),
                    "category" to (map["category"]?.toString()?.trim().orEmpty().ifBlank { "story" }),
                    "pinned" to ((map["pinned"] as? Boolean) == true || map["pinned"]?.toString() == "true"),
                )
            )
        }

        return mapOf(
            "session_summary" to sessionSummary.filterValues { hasMeaningfulValue(it) }
                .mapValues { trimText(it.value, 120) },
            "archived_summary" to compactArchived,
            "retrieved_memories" to compactHits,
            "scene_progress" to compactSceneProgress,
            "relation_delta" to compactRelationDelta,
            "character_snapshots" to compactSnapshots,
            "event_signals" to compactSignals,
            "world_facts" to compactWorldFacts,
            "controlled_memories" to compactControlled,
        ).filterValues { hasMeaningfulValue(it) }
    }

    private fun compactAssociationMemoryContext(memoryContext: Map<String, Any?>): Map<String, Any?> {
        val compact = compactMemoryContext(memoryContext)
        val sessionSummary = (compact["session_summary"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val summaryKeys = listOf(
            "current_location", "current_companions", "pending_commitments", "current_goal",
            "unresolved_threads", "recent_conflicts", "major_beats",
        )
        val controlledMemories = ((compact["controlled_memories"] as? List<*>) ?: emptyList<Any?>())
            .take(4).mapNotNull { item ->
                val map = (item as? Map<*, *>)?.mapKeys { it.key.toString() } ?: return@mapNotNull null
                mapOf(
                    "text" to trimText(map["text"], 180),
                    "category" to (map["category"]?.toString()?.trim().orEmpty().ifBlank { "story" }),
                    "pinned" to ((map["pinned"] as? Boolean) == true || map["pinned"]?.toString() == "true"),
                )
            }
        return mapOf(
            "session_summary" to summaryKeys.mapNotNull { key ->
                if (hasMeaningfulValue(sessionSummary[key])) key to sessionSummary[key] else null
            }.toMap(),
            "archived_summary" to (compact["archived_summary"] ?: emptyMap<Any?, Any?>()),
            "retrieved_memories" to ((compact["retrieved_memories"] as? List<*>) ?: emptyList<Any?>()).take(2),
            "controlled_memories" to controlledMemories,
        ).filterValues { hasMeaningfulValue(it) }
    }

    // ------------------------------------------------------------------
    // 对话回复消息（helpers.py build_dialogue_llm_messages）
    // ------------------------------------------------------------------

    fun buildDialogueLlmMessages(payload: Map<String, Any?>, retryOnEmpty: Boolean = false): List<LlmClient.ChatMessage> {
        val inputBlock = (payload["input"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val sessionMode = payload["mode"]?.toString()?.trim().orEmpty().ifBlank { "observe" }
        val includeInnerThoughts = payload["include_inner_thoughts"] == true ||
            payload["include_inner_thoughts"]?.toString() == "true"
        val messageKind = inputBlock["message_kind"]?.toString()?.trim().orEmpty().ifBlank { "dialogue" }
        val rawCard = payload["sillytavern_card"] as? Map<*, *>
        val cardName = rawCard?.get("name")?.toString().orEmpty()
        val positionedLore = (payload["world_book_matches"] as? List<*>).orEmpty()
            .filterIsInstance<WorldBookMatchDto>()
            .filter { it.status == "injected" && it.position != null }
            .take(DialoguePromptRules.WORLD_FACT_LIMIT)
        val depthLore = positionedLore.filter { it.position == WorldBookInjectionPosition.AtDepth }
        val cardUser = ((inputBlock["self_insert"] as? Map<*, *>)?.get("display_name") as? String)
            ?.takeIf { it.isNotBlank() } ?: inputBlock["speaker"]?.toString().orEmpty().ifBlank { "你" }
        val outlets = positionedLore.filter { it.position == WorldBookInjectionPosition.Outlet && it.outletName != null }
            .sortedByDescending { it.insertionOrder }
            .groupBy { it.outletName.orEmpty() }
            .mapValues { (_, entries) ->
                entries.joinToString("\n") { expandSillyTavernNames(it.excerpt, cardName, cardUser) }
            }
        val card = rawCard?.mapValues { (key, value) ->
            if (key is String && key in sillyTavernOutletCardFields && value is String) {
                expandSillyTavernOutlets(value, outlets).trim()
            } else value
        }
        val participants = (inputBlock["participants"] as? List<*>)?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val activeParticipants = (inputBlock["active_participants"] as? List<*>)?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val note = (payload["author_note"] as? AuthorNoteDto ?: AuthorNoteDto())
        val authorNote = buildAuthorNote(
            note, positionedLore, cardName.ifBlank { participants.singleOrNull() ?: "{{char}}" }, cardUser,
        )
        val inChatNote = authorNote?.takeIf { note.position == AuthorNotePosition.InChat }
        val characterNote = (card?.get("depth_prompt") as? String)?.takeIf(String::isNotBlank)?.let {
            InChatPrompt(
                depth = ((card["depth_prompt_depth"] as? Int) ?: 4).coerceIn(0, 10_000),
                role = card["depth_prompt_role"] as? WorldBookInjectionRole ?: WorldBookInjectionRole.System,
                text = it,
            )
        }
        val rawPersonas = payload["persona_contexts"] as? List<*> ?: emptyList<Any?>()
        val personaMap = linkedMapOf<String, Map<String, Any?>>()
        for (item in rawPersonas) {
            val map = (item as? Map<*, *>)?.mapKeys { it.key.toString() } ?: continue
            val name = map["name"]?.toString()?.trim().orEmpty()
            if (name.isNotEmpty()) personaMap[name] = map
        }
        val stablePersonaNames = mutableListOf<String>()
        for (name in participants + activeParticipants) {
            if (name in personaMap && name !in stablePersonaNames) stablePersonaNames.add(name)
        }
        val activePersonaNames = mutableListOf<String>()
        for (name in activeParticipants + participants) {
            if (name in personaMap && name !in activePersonaNames) activePersonaNames.add(name)
        }
        val stablePersonaContexts = stablePersonaNames.filterNot { it == cardName }.take(6)
            .map { compactStaticPersonaContext(personaMap[it]) }
        val activePersonaStates = activePersonaNames.take(6).map { compactActivePersonaState(personaMap[it]) }

        val relationExcerpt = trimText(
            ((payload["relation_context"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.get("relations_excerpt"))?.toString()?.trim(),
            1200,
        )
        val history = ((payload["history"] as? List<*>) ?: emptyList<Any?>()).takeLast(6)
        val memoryContext = compactMemoryContext(
            (payload["memory_context"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap(),
            omitPositionedLore = positionedLore.isNotEmpty(),
        ).toMutableMap()
        memoryContext.remove("relation_delta")
        memoryContext.remove("character_snapshots")
        memoryContext["event_signals"] = ((memoryContext["event_signals"] as? List<*>) ?: emptyList<Any?>()).takeLast(3)

        val knowledgeContext = ((payload["knowledge_context"] as? List<*>) ?: emptyList<Any?>())
            .takeLast(12).mapNotNull { item ->
                val map = (item as? Map<*, *>)?.mapKeys { it.key.toString() } ?: return@mapNotNull null
                val fact = map["fact"]?.toString()?.trim().orEmpty()
                if (fact.isEmpty()) return@mapNotNull null
                mapOf(
                    "fact" to trimText(fact, 120),
                    "holders" to ((map["holders"] as? List<*>) ?: emptyList<Any?>())
                        .mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }.take(8),
                )
            }

        val rawResponderHints = payload["responder_hints"] as? List<*> ?: emptyList<Any?>()
        val possibleResponders = linkedSetOf<String>()
        for (item in rawResponderHints) {
            val map = (item as? Map<*, *>)?.mapKeys { it.key.toString() } ?: continue
            val name = map["name"]?.toString()?.trim().orEmpty()
            if (name.isNotEmpty()) possibleResponders.add(name)
        }
        if (possibleResponders.isEmpty()) {
            possibleResponders.addAll(activeParticipants.ifEmpty { participants })
        }

        fun sourceEntryIsSafeForSharedGeneration(item: Map<String, Any?>): Boolean {
            val visibility = item["visibility"]?.toString()?.trim().orEmpty()
            if (visibility == "public") return true
            val allowed = ((item["allowed_characters"] as? List<*>) ?: emptyList<Any?>())
                .mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }.toSet()
            return visibility in setOf("scene", "private") &&
                possibleResponders.isNotEmpty() &&
                possibleResponders.all { it in allowed }
        }

        val originalSourceEntries = ((payload["original_source_context"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.get("entries") as? List<*> ?: emptyList<Any?>())
            .take(3).mapNotNull { item ->
                val map = (item as? Map<*, *>)?.mapKeys { it.key.toString() } ?: return@mapNotNull null
                if (map["source_id"]?.toString()?.trim().isNullOrEmpty()) return@mapNotNull null
                if (map["excerpt"]?.toString()?.trim().isNullOrEmpty()) return@mapNotNull null
                if (!sourceEntryIsSafeForSharedGeneration(map)) return@mapNotNull null
                mapOf(
                    "excerpt" to trimText(map["excerpt"], 320),
                    "visibility" to (map["visibility"]?.toString()?.trim().orEmpty()),
                    "allowed_characters" to ((map["allowed_characters"] as? List<*>) ?: emptyList<Any?>())
                        .mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() },
                )
            }

        val correctionContext = (payload["correction_context"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val instructions = (payload["instructions"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val hostAction = (payload["host_action"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val sceneCard = (payload["scene_card"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val responseLimit = (hostAction["response_limit_hint"] as? Number)?.toInt() ?: 2

        val stableSystemParts = mutableListOf<String>()
        stableSystemParts.add(payload["host_prompt_brief"]?.toString()?.trim().orEmpty())
        if (note.position == AuthorNotePosition.BeforePrompt && authorNote != null) {
            stableSystemParts.add(authorNote.text)
        }
        val generationGoal = instructions["generation_goal"]?.toString()?.trim().orEmpty()
        val cardSystem = card?.get("system_prompt")?.toString().orEmpty()
        stableSystemParts.add(if (cardSystem.isBlank()) generationGoal else {
            cardSystem.replace("{{original}}", generationGoal, ignoreCase = true)
        })
        stableSystemParts.add(instructions["mode_rule"]?.toString()?.trim().orEmpty())
        stableSystemParts.add(instructions["speaker_rule"]?.toString()?.trim().orEmpty())
        stableSystemParts.add(instructions["forbidden_speaker_rule"]?.toString()?.trim().orEmpty())
        stableSystemParts.add(instructions["response_style"]?.toString()?.trim().orEmpty())
        stableSystemParts.add(instructions["scene_rule"]?.toString()?.trim().orEmpty())
        stableSystemParts.add(hostAction["output_rule"]?.toString()?.trim().orEmpty())
        stableSystemParts.add(instructions["plugin_enhancer_rule"]?.toString()?.trim().orEmpty())
        stableSystemParts.add(promptLoader.getTurnSystemRule("small_action_rule"))
        stableSystemParts.add(promptLoader.getTurnSystemRule("json_only_rule"))
        stableSystemParts.add(promptLoader.getTurnSystemRule("one_response_per_speaker_rule"))
        // 提示词增强（比 Python 多一句，用户确认）：见 prompts/dialogue/turn_system.yaml 的 no_code_fence_rule
        stableSystemParts.add(promptLoader.getTurnSystemRule("no_code_fence_rule"))
        if (includeInnerThoughts) {
            stableSystemParts.add(promptLoader.getInnerThoughtRule())
        }
        val stableContext = mapOf(
            "mode" to sessionMode,
            "participants" to participants,
            "scene_card" to sceneCard,
            "persona_contexts" to stablePersonaContexts,
        )
        fun addWorldBookSection(position: WorldBookInjectionPosition, label: String) {
            val text = positionedLore.filter { it.position == position }
                .sortedByDescending { it.insertionOrder }.asReversed()
                .joinToString("\n") { it.excerpt }
            if (text.isNotBlank()) stableSystemParts.add("【世界书 · $label】\n$text")
        }
        addWorldBookSection(WorldBookInjectionPosition.BeforeCharacter, "角色定义前")
        stableSystemParts.add("STATIC_CHARACTER_CONTEXT\n" + compactJson(stableContext))
        if (card != null) {
            val sections = linkedMapOf(
                "玩家人设" to card["user_persona"],
                "角色描述" to card["description"],
                "性格摘要" to card["personality"],
                "场景" to card["scenario"],
            )
            for ((label, raw) in sections) {
                val text = raw?.toString().orEmpty()
                if (text.isNotBlank()) stableSystemParts.add("【$label】\n$text")
            }
        }
        addWorldBookSection(WorldBookInjectionPosition.AfterCharacter, "角色定义后")
        if (note.position == AuthorNotePosition.AfterPrompt && authorNote != null) {
            stableSystemParts.add(authorNote.text)
        }
        val examples = formatDialogueExamples(
            card?.get("mes_example")?.toString().orEmpty(),
            positionedLore,
            cardName.ifBlank { participants.singleOrNull() ?: "{{char}}" },
            cardUser,
        )
        if (examples.isNotBlank()) stableSystemParts.add("【对话示例】\n$examples")

        val turnSystemParts = mutableListOf<String>()
        turnSystemParts.add(instructions["progression_rule"]?.toString()?.trim().orEmpty())
        turnSystemParts.add(instructions["plot_progression_contract"]?.toString()?.trim().orEmpty())
        turnSystemParts.add(instructions["response_count_rule"]?.toString()?.trim().orEmpty())
        turnSystemParts.add(instructions["group_chat_rule"]?.toString()?.trim().orEmpty())
        turnSystemParts.add(instructions["mention_rule"]?.toString()?.trim().orEmpty())
        turnSystemParts.add(instructions["temporary_npc_rule"]?.toString()?.trim().orEmpty())

        val speakerPlan = (payload["speaker_plan"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val responderHints = rawResponderHints
        val speakerActivity = payload["speaker_activity"] as? List<*> ?: emptyList<Any?>()
        if (speakerPlan.isNotEmpty()) {
            turnSystemParts.add(promptLoader.getTurnSystemRule("speaker_plan_rule"))
        }
        if (knowledgeContext.isNotEmpty()) {
            turnSystemParts.add(promptLoader.getTurnSystemRule("knowledge_boundary_rule"))
        }
        if (originalSourceEntries.isNotEmpty()) {
            turnSystemParts.add(promptLoader.getTurnSystemRule("original_source_rule"))
        }
        val controlledMemories = memoryContext["controlled_memories"] as? List<*> ?: emptyList<Any?>()
        if (controlledMemories.isNotEmpty()) {
            turnSystemParts.add(promptLoader.getTurnSystemRule("controlled_memories_rule"))
        }
        val worldFacts = memoryContext["world_facts"] as? List<*> ?: emptyList<Any?>()
        if (worldFacts.isNotEmpty() || positionedLore.any {
            it.position != WorldBookInjectionPosition.BeforeExamples && it.position != WorldBookInjectionPosition.AfterExamples
        }) {
            turnSystemParts.add(promptLoader.getTurnSystemRule("world_facts_rule"))
        }
        if (correctionContext.isNotEmpty()) {
            turnSystemParts.add(promptLoader.getTurnSystemRule("correction_context_rule"))
        }
        if (retryOnEmpty) {
            turnSystemParts.add(promptLoader.getTurnSystemRule("retry_rule"))
            if (messageKind == "plot") {
                turnSystemParts.add(promptLoader.getTurnSystemRule("retry_plot_rule"))
            }
        }
        val stableSystemPrompt = stableSystemParts.filter { it.isNotEmpty() }.joinToString("\n")
        val turnSystemPrompt = turnSystemParts.filter { it.isNotEmpty() }.joinToString("\n")

        // 与 json_only_rule 保持一致：主协议是 NDJSON（每行一个对象），不能再给 responses 包装示例。
        // 优先复用 payload 里按 message_kind 生成的示例（与 Python build_dialogue_llm_messages 对齐）。
        val expectedOutput: Any = hostAction["expected_output"] ?: mapOf(
            "format" to "ndjson",
            "example" to if (includeInnerThoughts) {
                """{"speaker":"角色名","message":"回复内容","inner_thought":"角色没说出口的真实想法"}"""
            } else {
                """{"speaker":"角色名","message":"回复内容"}"""
            },
        )
        val userPayload = mapOf(
            "mode" to sessionMode,
            "message_kind" to messageKind,
            "speaker" to (inputBlock["speaker"]?.toString()?.trim().orEmpty()),
            "message" to (inputBlock["message"]?.toString()?.trim().orEmpty()),
            "participants" to participants,
            "active_participants" to activeParticipants,
            "allowed_responders" to (inputBlock["allowed_responders"] as? List<*> ?: emptyList<Any?>()),
            "forbidden_responders" to (inputBlock["forbidden_responders"] as? List<*> ?: emptyList<Any?>()),
            "mention_targets" to (inputBlock["mention_targets"] as? List<*> ?: emptyList<Any?>()),
            "memory_context" to memoryContext,
            "knowledge_boundary" to knowledgeContext,
            "original_source_context" to originalSourceEntries,
            "correction_context" to correctionContext,
            "response_limit" to responseLimit,
            "active_persona_state" to activePersonaStates,
            "speaker_plan" to speakerPlan,
            "responder_hints" to responderHints,
            "speaker_activity" to speakerActivity,
            "history_evidence" to (payload["history_evidence"] as? List<*> ?: emptyList<Any?>()),
            "history" to history,
            "relation_excerpt" to relationExcerpt,
            "expected_output" to expectedOutput,
            "retry_on_empty" to retryOnEmpty,
        )
        if (card != null || depthLore.isNotEmpty() || inChatNote != null) {
            val dialogueHistory = mutableListOf<LlmClient.ChatMessage>()
            val sceneHistory = mutableListOf<Map<*, *>>()
            for (entry in history.filterIsInstance<Map<*, *>>()) {
                val text = trimText(entry["message"], 4000)
                if (text.isBlank()) continue
                val role = when (entry["role"]) {
                    "user" -> "user"
                    "assistant", "character" -> "assistant"
                    else -> null
                }
                if (role == null) {
                    sceneHistory.add(mapOf("role" to entry["role"], "speaker" to entry["speaker"], "message" to text))
                } else {
                    val speaker = entry["speaker"]?.toString().orEmpty()
                    val content = if (card == null && speaker.isNotBlank()) "$speaker：$text" else text
                    dialogueHistory.add(LlmClient.ChatMessage(role = role, content = content))
                    val thought = trimText(entry["inner_thought"], 400)
                    if (card == null && thought.isNotBlank()) {
                        sceneHistory.add(mapOf("speaker" to speaker, "inner_thought" to thought))
                    }
                }
            }
            dialogueHistory.add(LlmClient.ChatMessage(role = "user", content = inputBlock["message"]?.toString().orEmpty()))
            val turnContext = userPayload - "message" + ("history" to sceneHistory)
            val postHistory = card?.get("post_history_instructions")?.toString().orEmpty()
                .replace("{{original}}", "", ignoreCase = true).trim()
            return buildList {
                add(LlmClient.ChatMessage(role = "system", content = stableSystemPrompt))
                add(LlmClient.ChatMessage(role = "system", content = "$turnSystemPrompt\nTURN_CONTEXT\n" + compactJson(turnContext)))
                addAll(insertDepthLore(dialogueHistory, depthLore, listOfNotNull(inChatNote, characterNote)))
                if (postHistory.isNotEmpty()) {
                    val contract = listOf(
                        hostAction["output_rule"]?.toString().orEmpty(),
                        promptLoader.getTurnSystemRule("json_only_rule"),
                        promptLoader.getTurnSystemRule("no_code_fence_rule"),
                    ).filter(String::isNotBlank).joinToString("\n")
                    add(LlmClient.ChatMessage(role = "system", content = "$postHistory\n$contract"))
                }
            }
        }
        val userPrompt = compactJson(userPayload)
        return listOf(
            LlmClient.ChatMessage(role = "system", content = stableSystemPrompt),
            LlmClient.ChatMessage(role = "system", content = turnSystemPrompt),
            LlmClient.ChatMessage(role = "user", content = userPrompt),
        )
    }

    private fun formatDialogueExamples(
        cardExamples: String,
        lore: List<WorldBookMatchDto>,
        character: String,
        user: String,
    ): String {
        val inserted = lore.filter {
            it.position == WorldBookInjectionPosition.BeforeExamples || it.position == WorldBookInjectionPosition.AfterExamples
        }.sortedByDescending { it.insertionOrder }
        if (inserted.isEmpty()) return cardExamples

        fun examples(entries: List<WorldBookMatchDto>, position: WorldBookInjectionPosition): List<String> =
            entries.filter { it.position == position }.flatMap {
                sillyTavernExampleBlocks(expandSillyTavernNames(it.excerpt, character, user))
            }
        val before = examples(inserted, WorldBookInjectionPosition.BeforeExamples)
        val after = examples(inserted.asReversed(), WorldBookInjectionPosition.AfterExamples)
        return (before + sillyTavernExampleBlocks(cardExamples) + after)
            .joinToString("\n\n") { "<START>\n$it" }
    }

    private data class InChatPrompt(val depth: Int, val role: WorldBookInjectionRole, val text: String)

    private fun buildAuthorNote(
        note: AuthorNoteDto,
        lore: List<WorldBookMatchDto>,
        character: String,
        user: String,
    ): InChatPrompt? {
        if (!note.enabled) return null
        val ordered = lore.filter { it.position?.isAuthorNote == true }.sortedByDescending { it.insertionOrder }.asReversed()
        val parts = ordered.filter { it.position == WorldBookInjectionPosition.BeforeAuthorNote }.map { it.excerpt } +
            note.text.trim().take(AuthorNoteDto.MAX_TEXT_LENGTH) +
            ordered.filter { it.position == WorldBookInjectionPosition.AfterAuthorNote }.map { it.excerpt }
        val text = expandSillyTavernNames(parts.filter(String::isNotBlank).joinToString("\n"), character, user)
        return text.takeIf(String::isNotBlank)?.let { InChatPrompt(note.depth.coerceIn(0, 10_000), note.role, it) }
    }

    private fun insertDepthLore(
        dialogue: List<LlmClient.ChatMessage>,
        lore: List<WorldBookMatchDto>,
        notes: List<InChatPrompt>,
    ): List<LlmClient.ChatMessage> {
        if (lore.isEmpty() && notes.isEmpty()) return dialogue
        // SillyTavern places depth prompts into newest-first history, then sends it in reverse.
        val roleOrder = listOf(WorldBookInjectionRole.Assistant, WorldBookInjectionRole.User, WorldBookInjectionRole.System)
        val byDepth = lore.groupBy { it.injectionDepth ?: 4 }
        val notesByDepth = notes.groupBy { it.depth }
        val pending = (byDepth.keys + notesByDepth.keys).sortedDescending()
            .groupBy { (dialogue.size - it).coerceIn(0, dialogue.size) }
        return buildList {
            for (index in 0..dialogue.size) {
                for (depth in pending[index].orEmpty()) {
                    val byRole = byDepth[depth].orEmpty().sortedByDescending { it.insertionOrder }.asReversed()
                        .groupBy { it.injectionRole ?: WorldBookInjectionRole.System }
                    for (role in roleOrder) {
                        val noteTexts = notesByDepth[depth].orEmpty().filter { it.role == role }.map { it.text }
                        val text = (noteTexts + byRole[role].orEmpty().map { it.excerpt }).joinToString("\n")
                        if (text.isNotBlank()) add(LlmClient.ChatMessage(role = role.chatRole, content = text))
                    }
                }
                dialogue.getOrNull(index)?.let(::add)
            }
        }
    }

    // ------------------------------------------------------------------
    // 续写建议消息（helpers.py build_dialogue_suggestion_llm_messages）
    // ------------------------------------------------------------------

    fun buildDialogueSuggestionLlmMessages(
        payload: Map<String, Any?>,
        retryOnEmpty: Boolean = false,
    ): List<LlmClient.ChatMessage> {
        val inputBlock = (payload["input"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val sessionMode = payload["mode"]?.toString()?.trim().orEmpty().ifBlank { "observe" }
        val participants = (inputBlock["participants"] as? List<*>)?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val personaContexts = payload["persona_contexts"] as? List<*> ?: emptyList<Any?>()
        val userPersona = (payload["user_persona"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val relationExcerpt = ((payload["relation_context"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.get("relations_excerpt"))?.toString()?.trim().orEmpty()
        val history = payload["history"] as? List<*> ?: emptyList<Any?>()
        val memoryContext = (payload["memory_context"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val sceneProgress = ((payload["scene_progress"] as? Map<*, *>)?.mapKeys { it.key.toString() })
            ?: (memoryContext["scene_progress"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?: emptyMap()
        val instructions = (payload["instructions"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val hostAction = (payload["host_action"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val sceneCard = (payload["scene_card"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val selectedDirection = payload["selected_direction"]?.toString()?.trim().orEmpty()
        val requestedSpeaker = payload["requested_speaker"]?.toString()?.trim().orEmpty()

        val systemParts = mutableListOf<String>()
        systemParts.add(payload["host_prompt_brief"]?.toString()?.trim().orEmpty())
        systemParts.add(promptLoader.getTurnSystemRule("suggestion_meta_rule"))
        systemParts.add(instructions["generation_goal"]?.toString()?.trim().orEmpty())
        systemParts.add(instructions["mode_rule"]?.toString()?.trim().orEmpty())
        systemParts.add(instructions["speaker_rule"]?.toString()?.trim().orEmpty())
        systemParts.add(instructions["response_style"]?.toString()?.trim().orEmpty())
        systemParts.add(instructions["scene_rule"]?.toString()?.trim().orEmpty())
        systemParts.add(hostAction["output_rule"]?.toString()?.trim().orEmpty())
        systemParts.add(promptLoader.getTurnSystemRule("user_persona_rule"))
        systemParts.add(promptLoader.getTurnSystemRule("insert_persona_rule"))
        systemParts.add(promptLoader.getTurnSystemRule("insert_core_rule"))
        systemParts.add(promptLoader.getTurnSystemRule("persona_priority_rule"))
        if (requestedSpeaker.isEmpty()) {
            systemParts.add(promptLoader.getTurnSystemRule("act_persona_rule"))
        }
        systemParts.add(promptLoader.getTurnSystemRule("observe_scene_rule"))
        systemParts.add(promptLoader.getTurnSystemRule("scene_progress_rule"))
        systemParts.add(promptLoader.getTurnSystemRule("observe_immediate_rule"))
        systemParts.add(promptLoader.getTurnSystemRule("anchor_rule"))
        systemParts.add(promptLoader.getTurnSystemRule("offstage_rule"))
        systemParts.add(promptLoader.getTurnSystemRule("scene_card_rule"))
        systemParts.add(promptLoader.getTurnSystemRule("output_complete_rule"))
        systemParts.add(promptLoader.getTurnSystemRule("no_analysis_rule"))
        systemParts.add(promptLoader.getTurnSystemRule("no_format_rule"))
        if (selectedDirection.isNotEmpty()) {
            systemParts.add(promptLoader.getTurnSystemRule("selected_direction_rule"))
            systemParts.add(promptLoader.getTurnSystemRule("selected_direction_intent_rule"))
        }
        if (retryOnEmpty) {
            systemParts.add(promptLoader.getTurnSystemRule("suggestion_retry_rule"))
        }
        val systemPrompt = systemParts.filter { it.isNotEmpty() }.joinToString("\n")

        val userPayload = mapOf(
            "mode" to sessionMode,
            "speaker" to (inputBlock["speaker"]?.toString()?.trim().orEmpty()),
            "seed_text" to (inputBlock["message"]?.toString()?.trim().orEmpty()),
            "selected_direction" to selectedDirection,
            "scene_card" to sceneCard,
            "scene_progress" to sceneProgress,
            "memory_context" to memoryContext,
            "user_persona" to userPersona,
            "participants" to participants,
            "persona_contexts" to personaContexts,
            "history" to history,
            "relation_excerpt" to relationExcerpt,
            "response_shape" to (hostAction["expected_output"]
                ?: mapOf("suggestion" to promptLoader.getTurnSystemRule("response_shape_default_suggestion"))),
            "good_examples" to mapOf(
                "act_or_insert" to listOf("抱歉，我刚才那句说重了。", "你先别气，我不是在呛你。", "那我换个说法，你别误会。"),
                "observe" to listOf(
                    "门外忽然传来两下敲门声，屋里一下静了。",
                    "江澄先看见了他袖口上的血，话到嘴边忽然顿住。",
                    "魏无羡低头笑了一下，却没立刻接这句话。",
                    "回廊外的雨忽然更近了，像是有人已经走到了檐下。",
                ),
            ),
            "bad_examples" to listOf(
                "我们作为“你”是误入此间的来客……",
                "当前场景是对方在生气，我们可以先安抚……",
                "建议回复：先道歉，再解释。",
                "你们继续聊下去吧。",
                "要不先让他们把刚才那句接下去？",
                "不如让场景自然推进到下一幕。",
            ),
            "retry_on_empty" to retryOnEmpty,
        )
        val userPrompt = compactJson(userPayload)
        return listOf(
            LlmClient.ChatMessage(role = "system", content = systemPrompt),
            LlmClient.ChatMessage(role = "user", content = userPrompt),
        )
    }

    // ------------------------------------------------------------------
    // 剧情联想消息（helpers.py build_dialogue_association_llm_messages）
    // ------------------------------------------------------------------

    fun buildDialogueAssociationLlmMessages(
        payload: Map<String, Any?>,
        retryOnEmpty: Boolean = false,
    ): List<LlmClient.ChatMessage> {
        val inputBlock = (payload["input"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val memoryContext = (payload["memory_context"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val instructions = (payload["instructions"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val hostAction = (payload["host_action"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val optionCount = ((instructions["option_count"] as? Number)?.toInt() ?: 3).coerceIn(2, 4)

        val responseShapeRaw = (hostAction["expected_output"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val optionShapes = ((responseShapeRaw["options"] as? List<*>) ?: emptyList<Any?>())
            .mapNotNull { (it as? Map<*, *>)?.mapKeys { key -> key.toString() }?.toMutableMap() }
            .toMutableList()
        if (optionShapes.isEmpty()) {
            optionShapes.add(
                mapOf<String, Any?>(
                    "label" to promptLoader.getTurnSystemRule("option_label_shape"),
                    "direction" to promptLoader.getTurnSystemRule("option_direction_shape"),
                    "anchor_speaker" to promptLoader.getTurnSystemRule("option_anchor_speaker_shape"),
                    "anchor_quote" to promptLoader.getTurnSystemRule("option_anchor_quote_shape"),
                ).toMutableMap()
            )
        }
        for (optionShape in optionShapes) {
            if (optionShape["suggestion"]?.toString().isNullOrEmpty()) {
                optionShape["suggestion"] = promptLoader.getTurnSystemRule("suggestion_shape")
            }
        }
        val responseShape = responseShapeRaw.toMutableMap()
        responseShape["options"] = optionShapes

        val systemPrompt = promptLoader.getDialogueSuggestionsPrompt(
            optionCount = optionCount,
            retry = retryOnEmpty,
            generationGoal = instructions["generation_goal"]?.toString()?.trim().orEmpty(),
            outputRule = hostAction["output_rule"]?.toString()?.trim().orEmpty(),
        )
        val sceneProgress = ((payload["scene_progress"] as? Map<*, *>)?.mapKeys { it.key.toString() })
            ?: (memoryContext["scene_progress"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?: emptyMap()

        val userPayload = mapOf(
            "mode" to (payload["mode"]?.toString()?.trim().orEmpty().ifBlank { "observe" }),
            "speaker" to (inputBlock["speaker"]?.toString()?.trim().orEmpty()),
            "latest_exchange" to ((payload["latest_exchange"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap<String, Any?>()),
            "participants" to ((inputBlock["participants"] as? List<*>)?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() } ?: emptyList()),
            "recent_completed_history" to compactAssociationHistory(payload["history"] as? List<*> ?: emptyList<Any?>()),
            "scene_card" to compactAssociationSceneCard((payload["scene_card"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()),
            "scene_progress" to compactAssociationSceneProgress(sceneProgress),
            "memory_anchors" to compactAssociationMemoryContext(memoryContext),
            "user_persona" to compactUserPersona(payload["user_persona"]),
            "persona_contexts" to ((payload["persona_contexts"] as? List<*>) ?: emptyList<Any?>())
                .take(4).map { compactPersonaContext(it) },
            "relation_excerpt" to trimText(
                ((payload["relation_context"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.get("relations_excerpt"))?.toString()?.trim(),
                800,
            ),
            "response_shape" to responseShape,
            "option_count" to optionCount,
            "retry_on_empty" to retryOnEmpty,
        )
        val userPrompt = compactJson(userPayload)
        return listOf(
            LlmClient.ChatMessage(role = "system", content = systemPrompt),
            LlmClient.ChatMessage(role = "user", content = userPrompt),
        )
    }

    // ------------------------------------------------------------------
    // 剧情导演消息（helpers.py build_dialogue_director_llm_messages）
    // ------------------------------------------------------------------

    fun buildDialogueDirectorLlmMessages(
        payload: Map<String, Any?>,
        retryOnEmpty: Boolean = false,
    ): List<LlmClient.ChatMessage> {
        val optionCount = (payload["option_count"] as? Number)?.toInt()?.coerceIn(2, 4) ?: 3
        val systemPrompt = promptLoader.getDialogueDirectorPrompt(
            optionCount = optionCount,
            retry = retryOnEmpty,
            action = (payload["director_action"]?.toString()?.trim().orEmpty().ifBlank { "advance" }),
        )
        val inputPayload = (payload["input"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val userPayload = mapOf(
            "director_goal" to (payload["director_goal"]?.toString()?.trim().orEmpty()),
            "director_action" to (payload["director_action"]?.toString()?.trim().orEmpty().ifBlank { "advance" }),
            "mode" to (payload["mode"]?.toString()?.trim().orEmpty().ifBlank { "observe" }),
            "participants" to (inputPayload["participants"] as? List<*> ?: emptyList<Any?>()),
            "active_participants" to (inputPayload["active_participants"] as? List<*> ?: emptyList<Any?>()),
            "scene_card" to ((payload["scene_card"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap<String, Any?>()),
            "scene_progress" to ((payload["scene_progress"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap<String, Any?>()),
            "latest_exchange" to ((payload["latest_exchange"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap<String, Any?>()),
            "memory_context" to compactMemoryContext(
                (payload["memory_context"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
            ),
            "relation_excerpt" to trimText(
                ((payload["relation_context"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.get("relations_excerpt"))?.toString()?.trim(),
                1200,
            ),
            "speaker_activity" to (payload["speaker_activity"] as? List<*> ?: emptyList<Any?>()),
            "option_count" to optionCount,
        )
        val userPrompt = compactJson(userPayload)
        return listOf(
            LlmClient.ChatMessage(role = "system", content = systemPrompt),
            LlmClient.ChatMessage(role = "user", content = userPrompt),
        )
    }

    // ------------------------------------------------------------------
    // 一致性审校消息（helpers.py build_dialogue_consistency_review_messages）
    // ------------------------------------------------------------------

    fun buildDialogueConsistencyReviewMessages(payload: Map<String, Any?>): List<LlmClient.ChatMessage> {
        val reviewPayload = mapOf(
            "mode" to (payload["mode"]?.toString()?.trim().orEmpty()),
            "participants" to (payload["participants"] as? List<*> ?: emptyList<Any?>()),
            "scene_progress" to ((payload["scene_progress"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap<String, Any?>()),
            "persona_contexts" to (payload["persona_contexts"] as? List<*> ?: emptyList<Any?>()),
            "relation_context" to ((payload["relation_context"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap<String, Any?>()),
            "knowledge_context" to (payload["knowledge_context"] as? List<*> ?: emptyList<Any?>()),
            "original_source_context" to (((payload["original_source_context"] as? Map<*, *>)
                ?.mapKeys { it.key.toString() }
                ?.get("entries") as? List<*>) ?: emptyList<Any?>()).take(3),
            "history" to (((payload["history"] as? List<*>) ?: emptyList<Any?>()).takeLast(8)),
            "input" to ((payload["input"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap<String, Any?>()),
            "responses" to (payload["responses"] as? List<*> ?: emptyList<Any?>()),
            "deterministic_report" to ((payload["deterministic_report"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap<String, Any?>()),
        )
        val systemPrompt = promptLoader.getConsistencyReviewPrompt()
        val userPrompt = compactJson(reviewPayload)
        return listOf(
            LlmClient.ChatMessage(role = "system", content = systemPrompt),
            LlmClient.ChatMessage(role = "user", content = userPrompt),
        )
    }
}

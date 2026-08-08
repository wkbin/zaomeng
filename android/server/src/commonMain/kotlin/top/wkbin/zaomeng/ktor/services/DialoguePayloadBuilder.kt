package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.platform.parseYaml
import top.wkbin.zaomeng.platform.randomUuid

/**
 * 对话 LLM payload 构建（迁移自 Python src/web/chat/service.py 的
 * `_build_turn_payload` 管道 + src/web/chat/speaker_balance.py）。
 *
 * 从 run manifest / session manifest / persona 档案 / 关系文件 / world_memory.json /
 * memory_ledger / transcript 组装结构化 payload，供 DialoguePromptBuilder 使用。
 *
 * 与 Python 的差异（Ktor 无对应数据源，按空值处理）：
 * - scene_progress / character_snapshots / event_signals（Ktor 会话无 state 快照）
 * - original_source_context（Ktor 无 novel 检索）
 * - knowledge_context / knowledge_ledger（Ktor 无一致性知识账本）
 * - 长期记忆 embedding 检索（retrieved_memories 为空）
 */
class DialoguePayloadBuilder(
    private val storage: StorageService,
) {
    /**
     * 进程级文件缓存（key=绝对路径；value=Pair<mtime, 解析结果>）。
     * persona/关系/world_memory 等只在导入/编辑时变化，文件未变则复用解析结果，避免每回复重复读+解析。
     */
    private companion object {
        private val fileCache = HashMap<String, Pair<Long, Any?>>()

        /** 按 mtime 缓存文件解析结果；文件未变时复用，否则重新加载并更新缓存。 */
        fun <T> cachedFileResult(key: String, mtime: Long, loader: () -> T): T {
            synchronized(fileCache) {
                fileCache[key]?.let { (cachedMtime, cached) ->
                    if (cachedMtime == mtime) {
                        @Suppress("UNCHECKED_CAST")
                        return cached as T
                    }
                }
            }
            val loaded = loader()
            synchronized(fileCache) {
                fileCache[key] = mtime to loaded
            }
            return loaded
        }
    }

    // ------------------------------------------------------------------
    // speaker_balance 迁移（Python src/web/chat/speaker_balance.py）
    // ------------------------------------------------------------------

    fun extractMentionTargets(activeParticipants: List<String>, message: String): List<String> {
        val text = message.orEmpty()
        val boundary = "(?=\$|\\s|，|。|！|？|；|：|、|（|）|\\(|\\)|,|\\.|!|\\?|;|:)"
        val longestAtPosition = mutableMapOf<Int, String>()
        for (item in activeParticipants) {
            val name = item.trim()
            if (name.isEmpty()) continue
            val pattern = Regex("@(" + Regex.escape(name) + ")" + boundary)
            for (match in pattern.findAll(text)) {
                val start = match.range.first
                val current = longestAtPosition[start].orEmpty()
                if (name.length > current.length) {
                    longestAtPosition[start] = name
                }
            }
        }
        val matched = longestAtPosition.values.toSet()
        return activeParticipants.map { it.trim() }.filter { it.isNotEmpty() && it in matched }
    }

    fun buildSpeakerActivity(
        participants: List<String>,
        completedTurns: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        val names = participants.map { it.trim() }.filter { it.isNotEmpty() }
        val totalTurns = completedTurns.size
        val activity = mutableListOf<Map<String, Any?>>()
        for (name in names) {
            val spokenTurns = mutableListOf<Int>()
            var replyCount = 0
            completedTurns.forEachIndexed { index, record ->
                val responses = ((record["result"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.get("responses") as? List<*>)
                    ?: (record["responses"] as? List<*>) ?: emptyList<Any?>()
                val matches = responses.filter { item ->
                    (item as? Map<*, *>)?.get("speaker")?.toString()?.trim() == name
                }
                if (matches.isNotEmpty()) {
                    spokenTurns.add(index + 1)
                    replyCount += matches.size
                }
            }
            val lastSpokeTurn = spokenTurns.lastOrNull() ?: 0
            val turnsSinceSpoke = if (lastSpokeTurn > 0) totalTurns - lastSpokeTurn else totalTurns
            val status = when {
                totalTurns == 0 -> "new"
                turnsSinceSpoke >= 3 -> "silent"
                turnsSinceSpoke >= 2 -> "due"
                else -> "active"
            }
            activity.add(
                mapOf(
                    "name" to name,
                    "reply_count" to replyCount,
                    "spoken_turns" to spokenTurns.size,
                    "total_turns" to totalTurns,
                    "last_spoke_turn" to lastSpokeTurn,
                    "turns_since_spoke" to turnsSinceSpoke,
                    "participation_rate" to (if (totalTurns > 0) {
                        (spokenTurns.size.toDouble() / totalTurns).let { Math.round(it * 1000) / 1000.0 }
                    } else {
                        0.0
                    }),
                    "status" to status,
                )
            )
        }
        return activity
    }

    fun buildSpeakerPlan(
        activity: List<Map<String, Any?>>,
        activeParticipants: List<String>,
        message: String,
        mode: String,
        inputSpeaker: String,
        controlledCharacter: String,
        messageKind: String,
        responseLimit: Int,
    ): Map<String, Any?> {
        val active = activeParticipants.map { it.trim() }.filter { it.isNotEmpty() }
        val speaker = inputSpeaker.trim()
        val controlled = controlledCharacter.trim()
        val kind = messageKind.trim()
        val isSceneKind = kind in setOf("narration", "plot")
        val eligible = mutableListOf<String>()
        for (name in active) {
            if (mode == "act" && !isSceneKind && name == speaker) continue
            if (name !in eligible) eligible.add(name)
        }
        val byName = linkedMapOf<String, Map<String, Any?>>()
        for (item in activity) {
            val map = item
            val name = map["name"]?.toString()?.trim().orEmpty()
            if (name.isNotEmpty()) byName[name] = map
        }
        val text = message.orEmpty()
        val directMentions = extractMentionTargets(eligible, text)
            .filter { controlled.isEmpty() || it != controlled }

        fun score(name: String): List<Any> {
            val row = byName[name] ?: emptyMap()
            val mentioned = when {
                name in directMentions -> 2
                text.contains(name) -> 1
                else -> 0
            }
            val silence = (row["turns_since_spoke"] as? Number)?.toInt() ?: 0
            val spokenTurns = (row["spoken_turns"] as? Number)?.toInt() ?: 0
            val controlledPenalty = if (isSceneKind && name == controlled) 1 else 0
            return listOf(-mentioned, -silence, controlledPenalty, spokenTurns, name)
        }

        // Python sorted(key=(-mentioned, -silence, controlled_penalty, spoken_turns, name)) 升序等价
        val ordered = eligible.sortedWith(Comparator { a, b ->
            val sa = score(a)
            val sb = score(b)
            for (i in 0..2) {
                val cmp = (sa[i] as Int).compareTo(sb[i] as Int)
                if (cmp != 0) return@Comparator cmp
            }
            val cmp3 = (sa[3] as Int).compareTo(sb[3] as Int)
            if (cmp3 != 0) return@Comparator cmp3
            (sa[4] as String).compareTo(sb[4] as String)
        })
        val limit = maxOf(1, minOf(responseLimit.takeIf { it > 0 } ?: 1, ordered.size.coerceAtLeast(1)))
        val recommended = ordered.take(limit)
        val silenceCandidates = ordered
            .filter { (((byName[it] ?: emptyMap())["turns_since_spoke"] as? Number)?.toInt() ?: 0) >= 2 }
            .take(limit)
        val priorityCandidates = (directMentions + silenceCandidates).distinct().take(limit)
        val reasons = linkedMapOf<String, String>()
        for (name in ordered) {
            val row = byName[name] ?: emptyMap()
            val turnsSince = (row["turns_since_spoke"] as? Number)?.toInt() ?: 0
            reasons[name] = when {
                name in directMentions -> "本轮被 @ 直接点名"
                text.contains(name) -> "本轮被提及"
                turnsSince >= 3 -> "已连续 $turnsSince 轮未发言"
                turnsSince >= 2 -> "近期较少参与"
                else -> "当前在场且适合回应"
            }
        }
        val rule = (
            if (directMentions.isNotEmpty()) {
                "用户明确 @ 了 ${directMentions.joinToString(", ")}；这些在场角色必须在本轮直接回应，且优先于未被点名的角色。"
            } else {
                "优先让被点名、与当前行动直接相关或较久未发言的在场角色自然介入；"
            }
            ) + "不要为了平均分配而强迫无关角色说话。"
        return mapOf(
            "order" to ordered,
            "recommended_speakers" to recommended,
            "mention_targets" to directMentions,
            "priority_candidates" to priorityCandidates,
            "reasons" to reasons,
            "response_limit" to limit,
            "rule" to rule,
        )
    }

    fun applyPlanToHints(
        hints: List<Map<String, String>>,
        plan: Map<String, Any?>,
    ): List<Map<String, String>> {
        val normalized = hints.filter { it["name"]?.trim().isNullOrEmpty().not() }
            .map { it.toMutableMap() }
        val urgent = ((plan["priority_candidates"] as? List<*>) ?: emptyList<Any?>())
            .mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }.toSet()
        val reasons = (plan["reasons"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val merged = mutableListOf<Map<String, String>>()
        for (item in normalized) {
            val name = item["name"]?.trim().orEmpty()
            if (name in urgent) item["priority"] = "urgent"
            item["reason"] = reasons[name]?.toString()?.trim().orEmpty()
            merged.add(item.mapValues { it.value })
        }
        return merged
    }

    // ------------------------------------------------------------------
    // 运行数据读取
    // ------------------------------------------------------------------

    private fun jsonMap(element: JsonObject?): Map<String, Any?> {
        if (element == null) return emptyMap()
        return element.mapValues { jsonValueToAny(it.value) }
    }

    private fun jsonValueToAny(value: kotlinx.serialization.json.JsonElement?): Any? = when (value) {
        null -> null
        is JsonObject -> value.mapValues { jsonValueToAny(it.value) }
        is JsonArray -> value.map { jsonValueToAny(it) }
        is kotlinx.serialization.json.JsonPrimitive ->
            if (value.isString) value.content else value.contentOrNull ?: value.toString()
    }

    private fun stringList(value: Any?): List<String> =
        (value as? List<*>)?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun mapValue(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()

    /** 读取人物档案 PROFILE.md 并解析为 Map（对齐 PersonaService.loadProfile 的解析顺序；按 mtime 缓存）。 */
    private fun parsePersonaFile(file: Path): Map<String, Any?> = cachedFileResult(file.toString(), storage.lastModifiedMillis(file)) {
        parsePersonaFileUncached(file)
    }

    private fun parsePersonaFileUncached(file: Path): Map<String, Any?> {
        if (!storage.exists(file) || !storage.isFile(file)) return emptyMap()
        val text = runCatching { storage.readText(file) }.getOrNull() ?: return emptyMap()
        fun loadYaml(source: String): Map<String, Any?>? = runCatching {
            @Suppress("UNCHECKED_CAST")
            (parseYaml(source) as? Map<String, Any>)?.mapValues { normalizeYamlValue(it.value) }
        }.getOrNull()

        val front = if (text.startsWith("---")) {
            text.split("---", limit = 3).getOrNull(1)
        } else {
            null
        }
        if (front != null) {
            loadYaml(front)?.let { return it }
        }
        loadYaml(text)?.let { return it }
        // Markdown 列表回退：- key: value
        val parsed = linkedMapOf<String, Any?>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("- ")) continue
            val body = trimmed.removePrefix("- ").trim()
            val colon = body.indexOf(':')
            if (colon <= 0) continue
            val key = body.substring(0, colon).trim()
            val value = body.substring(colon + 1).trim()
            val previous = parsed[key]
            parsed[key] = if (previous == null) value else "$previous；$value"
        }
        return parsed
    }

    private fun normalizeYamlValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.mapKeys { it.key.toString() }.mapValues { normalizeYamlValue(it.value) }
        is List<*> -> value.map { normalizeYamlValue(it) }
        else -> value
    }

    /** 组装 persona_contexts（对齐 Python _build_persona_contexts + persona_context.build_persona_contexts）。 */
    private fun buildPersonaContexts(
        runManifest: JsonObject,
        participants: List<String>,
        activeParticipants: List<String>,
        mode: String,
        controlledCharacter: String,
        snapshots: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val artifactIndex = runManifest["artifact_index"]?.jsonObject
        val characters = artifactIndex?.get("characters")?.jsonArray ?: JsonArray(emptyList())
        val personaMap = linkedMapOf<String, Map<String, Any?>>()
        for (item in characters) {
            val obj = item.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty()) continue
            val profileFile = obj["profile_file"]?.jsonPrimitive?.contentOrNull?.let { it.toPath() }
            val personaDir = obj["persona_dir"]?.jsonPrimitive?.contentOrNull?.let { it.toPath() }
            val candidates = mutableListOf<Path>()
            if (profileFile != null) {
                profileFile.parent?.let { candidates.add(it / "PROFILE.md") }
                candidates.add(profileFile)
            }
            if (personaDir != null) {
                candidates.add(personaDir / "PROFILE.md")
                candidates.add(personaDir / "PROFILE.generated.md")
            }
            val runDir = storage.getRunDirectory(runManifest["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
            candidates.add(runDir / "artifacts/characters/$name/PROFILE.md")
            candidates.add(runDir / "artifacts/characters/$name/PROFILE.generated.md")
            val existing = candidates.firstOrNull { storage.isFile(it) }
            val profile = existing?.let { parsePersonaFile(it) } ?: emptyMap()
            val preview = mapOf(
                "display_name" to (profile["display_name"]?.toString()?.trim().orEmpty()
                    .ifBlank { profile["core_identity"]?.toString()?.trim().orEmpty().ifBlank { name } }),
                "core_identity" to (profile["core_identity"]?.toString()?.trim().orEmpty()),
                "speech_style" to (profile["speech_style"]?.toString()?.trim().orEmpty()),
                "appearance_feature" to trimText(profile["appearance_feature"], 80),
            ).filterValues { it.isNotEmpty() }
            personaMap[name] = mapOf(
                "name" to name,
                "preview" to preview,
                // 对齐 Python _compact_persona_context：profile 只取 12 个关键字段并截断，避免完整档案撑大 prompt
                "profile" to compactPersonaProfile(profile),
                "session_snapshot" to (mapValue(snapshots[name])),
            )
        }
        val participantsNames = (participants + activeParticipants).distinct()
        val result = mutableListOf<Map<String, Any?>>()
        for (name in participantsNames) {
            val persona = personaMap[name] ?: continue
            result.add(persona)
        }
        return result
    }

    /**
     * 对齐 Python _compact_persona_context：profile 只取 12 个关键字段并截断。
     * 避免完整 PROFILE.md（可达数 KB/人）撑大 prompt → DeepSeek prefill 变慢（TTFT 长）。
     */
    private fun compactPersonaProfile(profile: Map<String, Any?>): Map<String, Any?> {
        val compact = linkedMapOf<String, Any?>()
        fun put(key: String, value: Any?) {
            val s = value?.toString()?.trim().orEmpty()
            if (s.isNotEmpty()) compact[key] = s
        }
        fun putList(key: String, value: Any?) {
            val items = when (value) {
                is List<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                is String -> value.split(Regex("[,，;；]"))
                    .map { it.trim() }.filter { it.isNotEmpty() }
                else -> emptyList()
            }
            if (items.isNotEmpty()) compact[key] = items
        }
        put("core_identity", profile["core_identity"])
        put("story_role", profile["story_role"])
        put("gender", profile["gender"])
        put("age_stage", profile["age_stage"])
        put("appearance_feature", trimText(profile["appearance_feature"], 100))
        put("habit_action", trimText(profile["habit_action"], 80))
        put("speech_style", profile["speech_style"])
        put("temperament_type", profile["temperament_type"])
        put("stress_response", profile["stress_response"])
        putList("key_bonds", profile["key_bonds"])
        putList("preference_like", profile["preference_like"])
        putList("dislike_hate", profile["dislike_hate"])
        return compact
    }

    /** 截断文本到 max 字符（对齐 Python _trim_text：超长截断并追加省略号）。 */
    private fun trimText(value: Any?, max: Int): String {
        val text = value?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text.length <= max) return text
        return text.take(max - 1).trimEnd() + "…"
    }

    /** 字符串列表截断（对齐 Python 列表字段的 _trim_text + 数量上限）。 */
    private fun compactStringList(value: List<String>?, maxItems: Int, maxItem: Int): List<String> {
        val items = value.orEmpty().mapNotNull { it.trim() }.filter { it.isNotEmpty() }
        return items.take(maxItems).map { if (it.length <= maxItem) it else it.take(maxItem - 1).trimEnd() + "…" }
    }

    /** 读取关系文件文本（对齐 Python relation_excerpt.build_relation_excerpt 的简化版）。 */
    private fun loadRelationExcerpt(runManifest: JsonObject): String {
        val runId = runManifest["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        var file: Path? = null
        val relationsFile = runManifest["artifact_index"]?.jsonObject
            ?.get("relation_graph")?.jsonObject
            ?.get("relations_file")?.jsonPrimitive?.contentOrNull
        if (!relationsFile.isNullOrBlank()) {
            val candidate = relationsFile.toPath()
            if (storage.isFile(candidate)) file = candidate
        }
        if (file == null) {
            val relationsDir = storage.getRunDirectory(runId) / "artifacts/relations"
            if (storage.isDirectory(relationsDir)) {
                file = storage.listFiles(relationsDir).firstOrNull { f ->
                    storage.isFile(f) && f.name.substringAfterLast('.', "") == "md" && f.name.contains("relation") && !f.name.endsWith(".mermaid.md")
                }
            }
        }
        val text = file?.let { cachedFileResult(it.toString(), storage.lastModifiedMillis(it)) {
            runCatching { storage.readText(it) }.getOrNull().orEmpty()
        } }.orEmpty()
        // 去掉 front-matter 与 mermaid 图，保留正文段落
        val cleaned = text
            .replace(Regex("```mermaid[\\s\\S]*?```"), "")
            .trim()
        return cleaned
    }

    /** 读取 world_memory.json 的 facts → world_facts（对齐 Python _compact_memory_context：≤18 条、字段截断）。 */
    private fun loadWorldFacts(runId: String): List<Map<String, Any?>> {
        val file = storage.getRunDirectory(runId) / "world_memory.json"
        if (!storage.isFile(file)) return emptyList()
        // 按 mtime 缓存解析+排序结果（world_memory 只在写入时变化）
        return cachedFileResult(file.toString(), storage.lastModifiedMillis(file)) {
            loadWorldFactsUncached(file)
        }
    }

    private fun loadWorldFactsUncached(file: Path): List<Map<String, Any?>> {
        val facts = runCatching {
            Json.parseToJsonElement(storage.readText(file)).jsonObject["facts"]?.jsonArray
        }.getOrNull() ?: return emptyList()
        val parsed = facts.mapNotNull { item ->
            val obj = item.jsonObject
            val summary = trimText(obj["summary"]?.jsonPrimitive?.contentOrNull, 240)
            if (summary.isEmpty()) return@mapNotNull null
            mapOf<String, Any?>(
                "fact_id" to (obj["fact_id"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                "category" to (obj["category"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                "summary" to summary,
                "characters" to compactStringList(
                    obj["characters"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull },
                    maxItems = 12, maxItem = 80,
                ),
                "location" to trimText(obj["location"]?.jsonPrimitive?.contentOrNull, 100),
                "time_hint" to trimText(obj["time_hint"]?.jsonPrimitive?.contentOrNull, 80),
                "locked" to (obj["locked"]?.jsonPrimitive?.contentOrNull == "true" || obj["locked"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true),
            )
        }
        // Python：locked 优先排序，取前 18 条
        return parsed.sortedBy { it["locked"] != true }.take(18)
    }

    /** 读取 session manifest 的 memory_ledger → controlled_memories（对齐 Python：≤20 条、text ≤500）。 */
    private fun loadControlledMemories(session: JsonObject): List<Map<String, Any?>> {
        val ledger = session["memory_ledger"]?.jsonArray ?: return emptyList()
        return ledger.mapNotNull { item ->
            val obj = item.jsonObject
            val rawText = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (rawText.isEmpty()) return@mapNotNull null
            val text = if (rawText.length <= 500) rawText else rawText.take(499).trimEnd() + "…"
            mapOf(
                "memory_id" to (obj["memory_id"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                "text" to text,
                "category" to (obj["category"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "story" }),
                "pinned" to (obj["pinned"]?.jsonPrimitive?.contentOrNull == "true" || obj["pinned"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true),
            )
        }.take(20)
    }

    /** 扫描已完成轮次的 responses 用于发言统计（从 session manifest 的 transcript 派生，避免每回复全量扫描 turns 目录）。 */
    private fun loadCompletedTurnRecords(session: JsonObject): List<Map<String, Any?>> {
        val transcript = session["transcript"]?.jsonArray ?: return emptyList()
        // 按 turn_id 分组（每轮含 user + N 条回复；同一 turn_id 归为一轮），取含角色回复的轮
        val byTurn = LinkedHashMap<String, MutableList<JsonElement>>()
        val order = mutableListOf<String>()
        transcript.forEach { entry ->
            val turnId = entry.jsonObject["turn_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            if (turnId !in byTurn) {
                byTurn[turnId] = mutableListOf()
                order.add(turnId)
            }
            byTurn.getValue(turnId).add(entry)
        }
        return order.mapNotNull { turnId ->
            val responses = byTurn.getValue(turnId).mapNotNull { entry ->
                val obj = entry.jsonObject
                val speaker = obj["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val role = obj["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (role == "character" && speaker.isNotEmpty()) {
                    mapOf(
                        "speaker" to speaker,
                        "message" to (obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                    )
                } else {
                    null
                }
            }
            if (responses.isEmpty()) null else mapOf("result" to mapOf("responses" to responses))
        }
    }

    /**
     * 规范化的场景进度（对齐 Python helpers.py:_canonical_scene_progress）。
     * 从 session["state"] 的 scene/presence/progression 汇总，并合并 session["scene_progress"] 覆盖。
     */
    private fun loadCanonicalSceneProgress(session: JsonObject): Map<String, Any?> {
        val state = session["state"]?.jsonObject ?: emptyMap()
        val scene = (state["scene"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val presence = (state["presence"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val progression = (state["progression"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val derived = linkedMapOf<String, Any?>(
            "present_participants" to (presence["present_participants"] ?: emptyList<Any?>()),
            "offstage_participants" to (presence["offstage_participants"] ?: emptyList<Any?>()),
            "time_hint" to (scene["time_hint"]?.toString()?.trim().orEmpty()),
            "location" to (scene["location"]?.toString()?.trim().orEmpty()),
            "atmosphere_summary" to (scene["atmosphere_summary"]?.toString()?.trim().orEmpty()),
            "progression_note" to (scene["progression_note"]?.toString()?.trim().orEmpty()),
            "should_offer_scene_shift" to (progression["should_offer_scene_shift"] ?: false),
            "scene_shift_reason" to (progression["scene_shift_reason"]?.toString()?.trim().orEmpty()),
            "turns_in_current_scene" to ((progression["turns_in_current_scene"] as? Number)?.toInt() ?: 0),
            "beat_maturity" to ((progression["beat_maturity"] as? Number)?.toInt() ?: 0),
            "world_tension_summary" to (progression["world_tension_summary"]?.toString()?.trim().orEmpty()),
            "updated_at" to listOf(
                progression["updated_at"]?.toString()?.trim(),
                presence["updated_at"]?.toString()?.trim(),
                scene["updated_at"]?.toString()?.trim(),
            ).firstOrNull { it.isNullOrEmpty().not() }.orEmpty(),
        )
        val merged = derived.toMutableMap()
        session["scene_progress"]?.jsonObject?.forEach { (key, value) ->
            merged[key] = jsonValueToAny(value)
        }
        return merged.filterValues { value ->
            when (value) {
                is String -> value.isNotEmpty()
                is List<*> -> value.isNotEmpty()
                is Boolean -> value
                is Number -> (value as? Int)?.let { it != 0 } ?: (value as? Double)?.let { it != 0.0 } ?: true
                else -> value != null
            }
        }
    }

    /** 事件信号（对齐 Python _canonical_event_signals：state.signals 或 session.event_signals）。 */
    private fun loadEventSignals(session: JsonObject): Map<String, Any?> {
        val state = session["state"]?.jsonObject ?: emptyMap()
        val signals = (state["signals"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?: session["event_signals"]?.jsonObject
            ?: SceneProgressState.emptyEventSignalsState()
        return signals
    }

    /** 角色快照（对齐 Python _canonical_character_snapshots）。 */
    private fun loadCharacterSnapshots(session: JsonObject): Map<String, Any?> {
        val state = session["state"]?.jsonObject ?: emptyMap()
        val characters = (state["characters"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val snapshots = (characters["snapshots"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?: session["character_snapshots"]?.jsonObject
            ?: emptyMap()
        return snapshots
    }

    // ------------------------------------------------------------------
    // _build_turn_payload 迁移
    // ------------------------------------------------------------------

    fun buildTurnPayload(
        runManifest: JsonObject,
        session: JsonObject,
        turnId: String,
        message: String,
        messageKind: String = "dialogue",
        speakerOverride: String = "",
        includeInnerThoughts: Boolean = false,
    ): Map<String, Any?> {
        val participants = stringList(jsonValueToAny(session["participants"]))
        val mode = session["mode"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "observe" }
        val normalizedMessageKind = DialoguePromptRules.normalizeMessageKind(messageKind)
        val speaker = speakerOverride.trim().ifEmpty {
            if (mode == "act") {
                session["controlled_character"]?.jsonPrimitive?.contentOrNull.orEmpty()
            } else if (mode == "insert") {
                (session["self_profile"]?.jsonObject?.get("display_name")?.jsonPrimitive?.contentOrNull).orEmpty()
                    .ifBlank { "你" }
            } else {
                "User"
            }
        }
        val sceneCard = session["scene_profile"]?.jsonObject?.let { jsonMap(it) }
            ?: session["scene_card"]?.jsonObject?.let { jsonMap(it) }
            ?: emptyMap()
        val selfInsert = session["self_profile"]?.jsonObject?.let { jsonMap(it) } ?: emptyMap()
        val transcript = (session["transcript"]?.jsonArray ?: JsonArray(emptyList()))
            .mapNotNull { it.jsonObject }
        val history = transcript.map { entry ->
            mapOf(
                "speaker" to (entry["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                "message" to (entry["message"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                "inner_thought" to (entry["inner_thought"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                "role" to (entry["role"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                "turn_id" to (entry["turn_id"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                "timestamp" to (entry["timestamp"]?.jsonPrimitive?.contentOrNull.orEmpty()),
            )
        }
        val latestHistory = history.takeLast(8)

        // 场景进度（对齐 Python _canonical_scene_progress + _canonical_event_signals）
        val sceneProgress = loadCanonicalSceneProgress(session)
        val eventSignals = loadEventSignals(session)
        val snapshots = loadCharacterSnapshots(session)
        val presentParticipants = (sceneProgress["present_participants"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        // 在场参与人：有 scene_progress 时以在场名单为准（对齐 Python service.py 的 active_participants）
        val activeParticipants = run {
            val deduped = mutableListOf<String>()
            val source = presentParticipants.ifEmpty { participants }
            for (name in source) {
                if (name.isNotEmpty() && name !in deduped) deduped.add(name)
            }
            val active = if (mode == "act") deduped.filter { it != speaker } else deduped
            active.ifEmpty { if (mode == "act") deduped.take(1) else deduped }
        }
        val mentionable = activeParticipants.filter { it != session["controlled_character"]?.jsonPrimitive?.contentOrNull?.trim() }
        val mentionTargets = extractMentionTargets(mentionable, message)

        val runId = runManifest["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sessionId = session["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val personaContexts = buildPersonaContexts(
            runManifest = runManifest,
            participants = participants,
            activeParticipants = activeParticipants,
            mode = mode,
            controlledCharacter = session["controlled_character"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            snapshots = snapshots,
        )
        val relationExcerpt = loadRelationExcerpt(runManifest).let { text ->
            if (text.length <= 1200) text else text.take(1199).trimEnd() + "…"
        }
        val memoryContext = mapOf(
            "session_summary" to emptyMap<String, Any?>(),
            "archived_summary" to emptyMap<String, Any?>(),
            "controlled_memories" to loadControlledMemories(session),
            "world_facts" to loadWorldFacts(runId),
            "retrieved_memories" to emptyList<Any?>(),
            "scene_progress" to sceneProgress,
            "relation_delta" to emptyMap<String, Any?>(),
            "character_snapshots" to snapshots,
            "event_signals" to SceneProgressState.buildSessionEventExcerpt(eventSignals),
        )

        val responseLimitHint = run {
            var limit = if (normalizedMessageKind == "plot") 3 else 2
            val required = mentionTargets.size + (if (normalizedMessageKind == "plot") 1 else 0)
            if (required > 0) limit = maxOf(limit, required)
            limit
        }
        val responseCountRule = if (normalizedMessageKind == "plot") {
            val characterReplyLimit = maxOf(0, responseLimitHint - 1)
            "Return exactly one concrete scene-level beat first as 场景提示 or 旁白" +
                (if (characterReplyLimit > 0) ", followed by 1-$characterReplyLimit present-character reactions." else ".")
        } else {
            "Return 1-$responseLimitHint in-world replies. " +
                "Let only characters who are currently present respond; do not force every participant to speak each turn."
        }
        val controlledCharacterName = session["controlled_character"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val instructions = mapOf(
            "mode" to mode,
            "generation_goal" to (
                if (normalizedMessageKind == "plot") {
                    "Materially advance the story while keeping every reply faithful to the persona bundle, relationship context, and scene mode."
                } else {
                    "Keep every reply faithful to the persona bundle, relationship context, and scene mode."
                }
                ),
            "mode_rule" to DialoguePromptRules.modeRule(mode, normalizedMessageKind, controlledCharacterName),
            "speaker_rule" to DialoguePromptRules.speakerRule(mode, session.toPayloadMap(), normalizedMessageKind),
            "response_style" to DialoguePromptRules.responseStyleRule(mode, normalizedMessageKind, controlledCharacterName),
            "scene_rule" to DialoguePromptRules.sceneRule(sceneCard),
            "progression_rule" to DialoguePromptRules.sceneProgressRule(sceneProgress),
            "plot_progression_contract" to DialoguePromptRules.plotProgressionContract(normalizedMessageKind, sceneProgress),
            "response_count_rule" to responseCountRule,
            "mention_rule" to (
                if (mentionTargets.isNotEmpty()) {
                    "The user directly addressed ${mentionTargets.joinToString(", ")} with @. Every mentioned character is present and must reply in this turn before optional unmentioned cast members."
                } else {
                    ""
                }
                ),
            "temporary_npc_rule" to (
                "When the scene genuinely needs a brief third-party intervention, you may introduce one named temporary NPC and give them a short in-character reply. " +
                    "Use a specific role-bearing name such as '店小二' or '巡夜人'; do not invent a protagonist, secret backstory, or a second NPC. " +
                    "Once introduced, that NPC remains available for later in-scene interaction until they leave."
                ),
        )

        val completedTurns = loadCompletedTurnRecords(session)
        val speakerActivity = buildSpeakerActivity(participants, completedTurns)
        val speakerPlan = buildSpeakerPlan(
            activity = speakerActivity,
            activeParticipants = activeParticipants,
            message = message,
            mode = mode,
            inputSpeaker = speaker,
            controlledCharacter = controlledCharacterName,
            messageKind = normalizedMessageKind,
            responseLimit = responseLimitHint,
        )
        val instructionsWithGroupChat = instructions.toMutableMap()
        instructionsWithGroupChat["group_chat_rule"] = speakerPlan["rule"]?.toString()?.trim().orEmpty()
        val responderHints = DialoguePromptRules.responderHints(
            mode, activeParticipants, speaker, normalizedMessageKind, controlledCharacterName,
        )
        val mergedResponderHints = applyPlanToHints(responderHints, speakerPlan)

        val expectedOutput: List<Any?> = if (normalizedMessageKind == "plot") {
            listOf(
                mapOf("speaker" to "场景提示", "message" to "A concrete event or state change happening now."),
                mapOf(
                    "speaker" to "CharacterName",
                    "message" to "An in-character reaction with a next hook.",
                    "inner_thought" to (if (includeInnerThoughts) "What the character thinks but does not say." else null),
                ).filterValues { it != null },
            )
        } else {
            listOf(
                mapOf(
                    "speaker" to "CharacterName",
                    "message" to "...",
                    "inner_thought" to (if (includeInnerThoughts) "What the character thinks but does not say." else null),
                ).filterValues { it != null },
            )
        }
        val outputRule = (
            if (normalizedMessageKind == "plot") {
                "Return the required scene-level beat first, then in-world character reactions. " +
                    "The scene beat must materially change the situation; do not use it for a minor gesture or a summary of existing dialogue. "
            } else {
                "Return only in-world character replies. " +
                    "Do not split obvious small actions into standalone narration; keep them inside the speaking character's line with brief parenthetical action. "
            }
            ) + "Do not explain the workflow or mention prompts."

        return mapOf(
            "kind" to "zaomeng_dialogue_turn",
            "include_inner_thoughts" to includeInnerThoughts,
            "run_id" to runId,
            "session_id" to sessionId,
            "turn_id" to turnId,
            "mode" to mode,
            "input" to mapOf(
                "speaker" to speaker,
                "message" to message,
                "message_kind" to normalizedMessageKind,
                "participants" to participants,
                "active_participants" to activeParticipants,
                "mention_targets" to mentionTargets,
                "controlled_character" to (session["controlled_character"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                "scene_card" to sceneCard,
                "scene_progress" to sceneProgress,
                "character_snapshots" to snapshots,
                "self_insert" to selfInsert,
            ),
            "history" to latestHistory,
            "scene_card" to sceneCard,
            "memory_context" to memoryContext,
            "original_source_context" to emptyMap<String, Any?>(),
            "knowledge_context" to emptyList<Any?>(),
            "scene_progress" to sceneProgress,
            "persona_contexts" to personaContexts,
            "relation_context" to mapOf(
                "graph" to jsonMap(runManifest["artifact_index"]?.jsonObject?.get("relation_graph")?.jsonObject),
                "relations_excerpt" to relationExcerpt,
            ),
            "instructions" to instructionsWithGroupChat,
            "responder_hints" to mergedResponderHints,
            "speaker_activity" to speakerActivity,
            "speaker_plan" to speakerPlan,
            "host_action" to mapOf(
                "expected_output" to expectedOutput,
                "response_limit_hint" to responseLimitHint,
                "output_rule" to outputRule,
            ),
            "host_prompt_brief" to DialoguePromptRules.hostPromptBrief(
                mode, speaker, participants, normalizedMessageKind, controlledCharacterName,
            ),
        )
    }

    // ------------------------------------------------------------------
    // suggestion / association / director payload（service.py:2056-2187）
    // ------------------------------------------------------------------

    fun buildSuggestionPayload(
        runManifest: JsonObject,
        session: JsonObject,
        seedText: String = "",
        direction: String = "",
    ): Map<String, Any?> {
        val payload = buildTurnPayload(
            runManifest = runManifest,
            session = session,
            turnId = "suggest-${randomUuid().replace("-", "").take(8)}",
            message = seedText,
        ).toMutableMap()
        val mode = payload["mode"]?.toString()?.trim().orEmpty().ifBlank { "observe" }
        val speaker = (payload["input"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.get("speaker")?.toString()?.trim().orEmpty()
        val participants = stringList((payload["input"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.get("participants"))
        payload["kind"] = "zaomeng_dialogue_suggestion"
        val selectedDirection = direction.trim()
        if (selectedDirection.isNotEmpty()) {
            payload["selected_direction"] = selectedDirection
        }
        val sceneProgress = mapValue(payload["scene_progress"])
        val sessionSummary = mapValue(mapValue(payload["memory_context"])["session_summary"])
        val personaContexts = ((payload["persona_contexts"] as? List<*>) ?: emptyList<Any?>())
            .mapNotNull { (it as? Map<*, *>)?.mapKeys { key -> key.toString() } }
        val sessionMap = jsonMap(session)
        payload["user_persona"] = DialoguePromptRules.buildUserSuggestionPersona(
            mode, sessionMap, personaContexts, sceneProgress, sessionSummary,
        )
        payload["instructions"] = mapOf(
            "mode" to mode,
            "generation_goal" to "Draft one complete, natural, directly sendable next user message that fits the current scene, " +
                "relationships, and persona voices. Use one to three sentences when the selected direction needs room to land.",
            "mode_rule" to DialoguePromptRules.suggestionModeRule(mode),
            "speaker_rule" to DialoguePromptRules.speakerRule(mode, sessionMap),
            "response_style" to DialoguePromptRules.suggestionStyleRule(mode),
        )
        payload["host_action"] = mapOf(
            "expected_output" to mapOf("suggestion" to "一段完整、可直接发送的文案"),
            "output_rule" to "Keep it complete, in-scene, directly sendable, and never explanatory.",
        )
        payload["host_prompt_brief"] = DialoguePromptRules.hostSuggestionPromptBrief(mode, speaker, participants, sceneProgress)
        return payload
    }

    fun buildAssociationPayload(
        runManifest: JsonObject,
        session: JsonObject,
        optionCount: Int = 3,
    ): Map<String, Any?> {
        val payload = buildSuggestionPayload(runManifest, session).toMutableMap()
        val count = optionCount.coerceIn(2, 4)
        payload["kind"] = "zaomeng_dialogue_associations"
        payload["latest_exchange"] = buildLatestExchange(session)
        payload["instructions"] = mapOf(
            "generation_goal" to "Propose distinct user-facing next directions that continue directly from " +
                "the completed latest exchange. Treat older scene and relationship context " +
                "as background only.",
            "option_count" to count,
        )
        payload["host_action"] = mapOf(
            "expected_output" to mapOf(
                "options" to listOf(
                    mapOf(
                        "label" to "4-10字的推进选项",
                        "direction" to "供下一步代写使用的明确剧情方向",
                        "suggestion" to "一至三句可直接发送的成品文案",
                        "anchor_speaker" to "该方向所依据的最新回复角色",
                        "anchor_quote" to "从该角色最新回复中原样摘录的4-20字",
                    )
                ),
            ),
            "output_rule" to "Return exactly the requested number of options as JSON. " +
                "Every option must cite an exact anchor from the latest replies. " +
                "Never present a completed event as a future direction or invent a new fact.",
        )
        return payload
    }

    fun buildDirectorPayload(
        runManifest: JsonObject,
        session: JsonObject,
        goal: String,
        action: String = "advance",
        optionCount: Int = 3,
    ): Map<String, Any?> {
        val normalizedGoal = DialoguePromptRules.trimSummaryText(goal.trim(), 240)
        if (normalizedGoal.isEmpty()) throw IllegalArgumentException("请先填写导演目标。")
        val normalizedAction = action.trim().lowercase()
        if (normalizedAction !in setOf("advance", "slow_emotion", "conflict", "viewpoint")) {
            throw IllegalArgumentException("不支持的导演操作。")
        }
        val payload = buildSuggestionPayload(runManifest, session).toMutableMap()
        payload["kind"] = "zaomeng_dialogue_director_options"
        payload["director_goal"] = normalizedGoal
        payload["director_action"] = normalizedAction
        payload["option_count"] = optionCount.coerceIn(2, 4)
        payload["latest_exchange"] = buildLatestExchange(session)
        return payload
    }

    fun buildLatestExchange(session: JsonObject): Map<String, Any?> {
        val transcript = (session["transcript"]?.jsonArray ?: JsonArray(emptyList()))
            .mapNotNull { it.jsonObject }
            .filter { it["message"]?.jsonPrimitive?.contentOrNull?.isNotEmpty() == true }
        val mode = session["mode"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "observe" }
        val anchorRoles = if (mode in setOf("act", "insert")) setOf("user") else setOf("director")
        var anchorIndex = -1
        for (index in transcript.indices.reversed()) {
            val role = transcript[index]["role"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (role in anchorRoles) {
                anchorIndex = index
                break
            }
        }
        val userTurn: Map<String, Any?>
        val replies: List<Map<String, Any?>>
        if (anchorIndex >= 0) {
            val exchange = transcript.drop(anchorIndex)
            userTurn = jsonMap(exchange.firstOrNull())
            replies = exchange.drop(1).map { jsonMap(it) }
        } else {
            userTurn = emptyMap()
            replies = transcript.takeLast(4).map { jsonMap(it) }
        }
        val trimmedReplies = if (replies.size > 6) replies.takeLast(6) else replies
        val participants = stringList(jsonValueToAny(session["participants"]))
        val present = participants
        return mapOf(
            "status" to "completed",
            "user_turn" to userTurn,
            "replies" to trimmedReplies,
            "latest_reply" to (trimmedReplies.lastOrNull() ?: emptyMap<String, Any?>()),
            "speakers_who_just_replied" to trimmedReplies
                .mapNotNull { it["speaker"]?.toString()?.trim() }.filter { it.isNotEmpty() },
            "present_participants" to present,
            "offstage_participants" to emptyList<Any?>(),
        )
    }

    // ------------------------------------------------------------------
    // 一致性审校 payload（service.py build_consistency_review_payload）
    // ------------------------------------------------------------------

    fun buildConsistencyReviewPayload(
        runManifest: JsonObject,
        session: JsonObject,
        responses: List<Map<String, Any?>>,
    ): Map<String, Any?> {
        val participants = stringList(jsonValueToAny(session["participants"]))
        val history = (session["transcript"]?.jsonArray ?: JsonArray(emptyList()))
            .mapNotNull { it.jsonObject }.takeLast(8).map { jsonMap(it) }
        return mapOf(
            "mode" to (session["mode"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()),
            "participants" to participants,
            "scene_progress" to emptyMap<String, Any?>(),
            "persona_contexts" to emptyList<Any?>(),
            "relation_context" to emptyMap<String, Any?>(),
            "knowledge_context" to emptyList<Any?>(),
            "history" to history,
            "input" to emptyMap<String, Any?>(),
            "responses" to responses,
            "deterministic_report" to emptyMap<String, Any?>(),
        )
    }

    private fun JsonObject.toPayloadMap(): Map<String, Any?> = jsonMap(this)
}

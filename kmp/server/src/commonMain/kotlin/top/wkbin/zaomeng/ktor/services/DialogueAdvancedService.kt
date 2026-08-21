package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.randomUuid
import top.wkbin.zaomeng.data.api.MemoryQualityReportDto

/**
 * 对话高级功能服务
 *
 * 对应 Python 的 WebRunService 中的高级对话操作：
 * 会话搜索/恢复、分支、分支元数据、关系锁定、记忆管理、
 * 续写建议、修正、深度审校、剧情导演、场景卡切换与推荐。
 *
 * 会话清单（session_manifest.json）采用无损 JSON 更新：读取后仅修改
 * 相关字段并保留其余未知字段，保证与 App 端 DTO 兼容。
 */
class DialogueAdvancedService(
    private val storage: StorageService,
    private val llm: LlmClient?,
    private val prompts: PromptLoader?,
) {
    companion object {
        private const val TAG = "DialogueAdvanced"
        private val MEMORY_CATEGORIES = setOf("story", "short_term", "long_term", "relationship")
        private const val MAX_ENABLED_MEMORIES = 20
        private const val MAX_LEDGER_ENTRIES = 100
        private const val MAX_BRANCH_META_LABEL = 80
    }

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }
    private val longTermMemory = LongTermMemoryService(storage)
    private val tensionService = SceneTensionService()

    // ------------------------------------------------------------------
    // 只读操作
    // ------------------------------------------------------------------

    /**
     * 获取当前场景的剧情张力评估指数。
     */
    fun getSceneTension(runId: String, sessionId: String): top.wkbin.zaomeng.data.api.SceneTensionDto {
        val session = requireSession(runId, sessionId)
        val transcript = transcriptOf(session)
        val recentMessages = transcript.takeLast(10).mapNotNull {
            it["message"]?.jsonPrimitive?.contentOrNull
        }
        return tensionService.evaluateTension(recentMessages)
    }

    /**
     * 获取内置突发事件预设列表。
     */
    fun getPresetEvents(category: String? = null): List<top.wkbin.zaomeng.data.api.PlotEventPresetDto> {
        return tensionService.getPresetEvents(category)
    }

    /**
     * 搜索会话内容（transcript + 长期记忆）。
     * 对应 Python: search_dialogue_session
     */
    fun search(runId: String, sessionId: String, query: String, limit: Int): JsonArray {
        val session = requireSession(runId, sessionId)
        val needle = query.trim().lowercase()
        val results = mutableListOf<JsonObject>()
        transcriptOf(session).asReversed().forEach { item ->
            val speaker = item["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val message = item["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (speaker.lowercase().contains(needle) || message.lowercase().contains(needle)) {
                results.add(
                    buildJsonObject {
                        put("speaker", speaker)
                        put("message", message)
                        put("role", item["role"]?.jsonPrimitive?.contentOrNull ?: "character")
                        item["turn_id"]?.let { put("turn_id", it) }
                        item["timestamp"]?.let { put("timestamp", it) }
                        put("archived", false)
                        put("score", 1.0)
                    },
                )
            }
            if (results.size >= limit) return@forEach
        }
        if (results.size < limit) {
            val fromMemory = memoryLedgerOf(session)
                .filter { memory ->
                    val text = memory["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    text.lowercase().contains(needle)
                }
                .take(limit - results.size)
                .map { memory ->
                    buildJsonObject {
                        put("speaker", "长期记忆")
                        put("message", memory["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        put("role", "memory")
                        put("memory_id", memory["memory_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        put("archived", true)
                        put("score", 0.6)
                    }
                }
            results.addAll(fromMemory)
        }
        return buildJsonArray { results.forEach(::add) }
    }

    /**
     * 恢复会话。Ktor 后端无跨进程活跃操作概念，直接返回当前会话。
     * 对应 Python: recover_dialogue_session
     */
    fun recover(runId: String, sessionId: String, force: Boolean): JsonObject {
        val session = requireSession(runId, sessionId)
        return if (!force) session else markSessionRecovered(session)
    }

    // ------------------------------------------------------------------
    // 分支
    // ------------------------------------------------------------------

    /**
     * 从指定场景索引创建分支。
     * 对应 Python: branch_dialogue_session_from_scene
     */
    fun branchFromScene(runId: String, sessionId: String, sceneIndex: Int): JsonObject {
        require(sceneIndex >= 0) { "scene_index 不能为负数" }
        val session = requireSession(runId, sessionId)
        val transcript = transcriptOf(session)
        val sceneStarts = transcript.mapIndexedNotNull { index, item ->
            val role = item["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (role == "scene") index else null
        }
        val cutoff = if (sceneStarts.isNotEmpty()) {
            sceneStarts.getOrNull(sceneIndex) ?: sceneStarts.last()
        } else {
            // 无场景条目时退化为按轮次截断
            val turnBoundaries = transcript.mapIndexedNotNull { index, item ->
                item["turn_id"]?.let { index }
            }.distinct()
            turnBoundaries.getOrNull(sceneIndex) ?: transcript.size
        }
        return forkSession(
            runId = runId,
            source = session,
            sourceTurnId = transcript.getOrNull(cutoff)?.get("turn_id")?.jsonPrimitive?.contentOrNull.orEmpty(),
            keepFrom = cutoff,
        )
    }

    /**
     * 从指定 turn 创建分支。
     * 对应 Python: branch_dialogue_session_from_turn
     */
    fun branchFromTurn(runId: String, sessionId: String, turnId: String): JsonObject {
        require(turnId.isNotBlank()) { "turn_id 不能为空" }
        val session = requireSession(runId, sessionId)
        val transcript = transcriptOf(session)
        val lastIndexOfTurn = transcript.indexOfLast { it["turn_id"]?.jsonPrimitive?.contentOrNull == turnId }
        if (lastIndexOfTurn < 0) {
            throw NoSuchElementException("Turn not found: $turnId")
        }
        return forkSession(
            runId = runId,
            source = session,
            sourceTurnId = turnId,
            keepFrom = lastIndexOfTurn + 1,
        )
    }

    /**
     * 更新分支元数据。
     * 对应 Python: update_dialogue_branch_metadata
     */
    fun updateBranchMeta(
        runId: String,
        sessionId: String,
        label: String?,
        isMainline: Boolean?,
        lockedEventIds: List<String>?,
    ): JsonObject {
        label?.let {
            require(it.length <= MAX_BRANCH_META_LABEL) { "label 最多 $MAX_BRANCH_META_LABEL 个字符" }
        }
        lockedEventIds?.let {
            require(it.size <= 100) { "locked_event_ids 最多 100 个" }
        }
        val session = requireSession(runId, sessionId)
        val existing = session["branch_meta"]?.jsonObject ?: JsonObject(emptyMap())
        val updatedMeta = buildJsonObject {
            existing.forEach { (key, value) -> put(key, value) }
            label?.let { put("label", it) }
            isMainline?.let { put("is_mainline", it) }
            lockedEventIds?.let { ids ->
                put("locked_event_ids", buildJsonArray { ids.forEach { id -> add(JsonPrimitive(id)) } })
            }
        }
        // is_mainline=true 时清除同一 run 内其他会话的 is_mainline
        if (isMainline == true) {
            storage.listDialogueSessionIds(runId).forEach { otherId ->
                if (otherId != sessionId) {
                    val other = runCatching { storage.getDialogueSession(runId, otherId) }.getOrNull() ?: return@forEach
                    val otherMeta = other["branch_meta"]?.jsonObject ?: return@forEach
                    if (otherMeta["is_mainline"]?.jsonPrimitive?.booleanOrNull == true) {
                        saveSession(runId, otherId, other, extra = buildJsonObject {
                            put(
                                "branch_meta",
                                buildJsonObject {
                                    otherMeta.forEach { (key, value) -> put(key, value) }
                                    put("is_mainline", false)
                                },
                            )
                        })
                    }
                }
            }
        }
        return saveSession(runId, sessionId, session, extra = buildJsonObject {
            put("branch_meta", updatedMeta)
        })
    }

    // ------------------------------------------------------------------
    // 关系锁定 / 记忆管理
    // ------------------------------------------------------------------

    /**
     * 锁定/解锁角色关系。
     * 对应 Python: set_dialogue_relation_lock
     */
    fun setRelationLock(runId: String, sessionId: String, pairKey: String, locked: Boolean): JsonObject {
        require(pairKey.isNotBlank()) { "pair_key 不能为空" }
        val session = requireSession(runId, sessionId)
        val existing = session["relation_locks"]?.jsonObject ?: JsonObject(emptyMap())
        val updated = buildJsonObject {
            existing.forEach { (key, value) -> if (key != pairKey) put(key, value) }
            if (locked) put(pairKey, true)
        }
        return saveSession(runId, sessionId, session, extra = buildJsonObject {
            put("relation_locks", updated)
        })
    }

    /**
     * 创建或更新对话记忆。
     * 对应 Python: save_dialogue_memory（POST 无 memory_id，PUT 带 memory_id）
     */
    fun saveMemory(
        runId: String,
        sessionId: String,
        memoryId: String,
        text: String,
        category: String,
        pinned: Boolean,
        enabled: Boolean,
    ): JsonObject {
        require(text.isNotBlank() && text.length <= 500) { "记忆文本需为 1-500 字" }
        require(category in MEMORY_CATEGORIES) { "无效的记忆分类: $category" }
        val session = requireSession(runId, sessionId)
        val ledger = memoryLedgerOf(session)
        val timestamp = nowIsoString()
        val targetId = memoryId.ifBlank { "mem-" + randomUuid().replace("-", "").take(10) }
        val existingIndex = ledger.indexOfFirst {
            it["memory_id"]?.jsonPrimitive?.contentOrNull == targetId
        }
        val updatedLedger = ledger.toMutableList().apply {
            if (existingIndex >= 0) {
                val old = this[existingIndex]
                this[existingIndex] = buildJsonObject {
                    old.forEach { (key, value) -> put(key, value) }
                    put("text", text)
                    put("category", category)
                    put("pinned", pinned)
                    put("enabled", enabled)
                    put("updated_at", timestamp)
                }
            } else {
                add(
                    buildJsonObject {
                        put("memory_id", targetId)
                        put("text", text)
                        put("category", category)
                        put("pinned", pinned)
                        put("enabled", enabled)
                        put("created_at", timestamp)
                        put("updated_at", timestamp)
                    },
                )
            }
        }
        // enabled 最多 20 条，超出则保留最新 20 条
        val enabledCount = updatedLedger.count {
            it["enabled"]?.jsonPrimitive?.booleanOrNull != false
        }
        if (enabledCount > MAX_ENABLED_MEMORIES) {
            var toDisable = enabledCount - MAX_ENABLED_MEMORIES
            updatedLedger.indices.forEach { index ->
                val item = updatedLedger[index]
                if (toDisable > 0 && item["enabled"]?.jsonPrimitive?.booleanOrNull != false && item["memory_id"]?.jsonPrimitive?.contentOrNull != targetId) {
                    updatedLedger[index] = buildJsonObject {
                        item.forEach { (key, value) -> put(key, value) }
                        put("enabled", false)
                    }
                    toDisable--
                }
            }
        }
        // ledger 截断到最近 100 条
        val trimmed = updatedLedger.takeLast(MAX_LEDGER_ENTRIES)
        return saveSession(runId, sessionId, session, extra = buildJsonObject {
            put("memory_ledger", buildJsonArray { trimmed.forEach(::add) })
        })
    }

    /**
     * 删除对话记忆。
     * 对应 Python: delete_dialogue_memory
     */
    fun deleteMemory(runId: String, sessionId: String, memoryId: String): JsonObject {
        require(memoryId.isNotBlank()) { "memory_id 不能为空" }
        val session = requireSession(runId, sessionId)
        val filtered = memoryLedgerOf(session).filterNot {
            it["memory_id"]?.jsonPrimitive?.contentOrNull == memoryId
        }
        return saveSession(runId, sessionId, session, extra = buildJsonObject {
            put("memory_ledger", buildJsonArray { filtered.forEach(::add) })
        })
    }

    fun memoryQuality(runId: String, sessionId: String): MemoryQualityReportDto {
        requireSession(runId, sessionId)
        return longTermMemory.qualityReport(runId, sessionId)
    }

    fun updateAutomaticMemoryStatus(
        runId: String,
        sessionId: String,
        memoryId: String,
        status: String,
    ): MemoryQualityReportDto {
        requireSession(runId, sessionId)
        return longTermMemory.updateStatus(runId, sessionId, memoryId, status)
    }

    fun mergeDuplicateMemories(runId: String, sessionId: String): MemoryQualityReportDto {
        requireSession(runId, sessionId)
        return longTermMemory.mergeDuplicates(runId, sessionId)
    }

    // ------------------------------------------------------------------
    // LLM 功能
    // ------------------------------------------------------------------

    /**
     * 生成续写建议。
     * 对应 Python: suggest_dialogue_turn
     */
    suspend fun suggestDialogue(
        runId: String,
        sessionId: String,
        seedText: String,
        direction: String,
        speakerOverride: String = "",
    ): JsonObject {
        val session = requireSession(runId, sessionId)
        val client = requireNotNull(llm) { "LLM 客户端未配置" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置" }
        val runManifest = storage.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val payload = DialoguePayloadBuilder(storage).buildSuggestionPayload(
            runManifest = runManifest,
            session = session,
            seedText = seedText,
            direction = direction,
            speakerOverride = speakerOverride,
        )
        val messages = DialoguePromptBuilder(loader).buildDialogueSuggestionLlmMessages(
            payload = payload,
            retryOnEmpty = false,
        )
        val content = client.chatCompletion(
            messages = messages,
            temperature = 0.8,
            maxTokens = 512,
        ).choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (content.isBlank()) throw IllegalStateException("续写建议为空")
        val suggestion = parseSuggestion(content)
        return buildJsonObject { put("suggestion", suggestion) }
    }

    /**
     * 解析 LLM 续写建议输出：
     * 优先提取 JSON 中的 suggestion / options[0].suggestion，
     * 否则回退为去除引号后的原始文本。
     */
    private fun parseSuggestion(content: String): String {
        val fenceStripped = stripCodeFences(content)
        val fromJson = runCatching {
            val element = json.parseToJsonElement(fenceStripped)
            if (element is JsonObject) {
                val direct = element["suggestion"]?.jsonPrimitive?.contentOrNull
                if (direct != null) {
                    direct
                } else {
                    element["options"]?.jsonArray?.firstOrNull()?.jsonObject?.get("suggestion")?.jsonPrimitive?.contentOrNull
                }
            } else {
                null
            }
        }.getOrNull()
        if (fromJson != null) return fromJson.trim()
        return fenceStripped
            .removeSurrounding("\"", "\"")
            .removeSurrounding("'", "'")
            .trim()
            .takeIf(String::isNotBlank)
            ?: throw IllegalStateException("续写建议为空")
    }

    private fun stripCodeFences(content: String): String =
        content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

    /**
     * 修正最新回复：重新生成最后一条角色回复并替换。
     * 对应 Python: correct_latest_dialogue_turn（走完整 reply 管道 + CORRECTION_CONTEXT，
     * 在当会话内替换最后一轮角色回复，而不是创建独立分支）。
     */
    suspend fun correctLatest(runId: String, sessionId: String): JsonObject {
        val session = requireSession(runId, sessionId)
        val runManifest = storage.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val transcript = transcriptOf(session)
        val lastRoleIndex = transcript.indexOfLast {
            val role = it["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
            role != "user" && role != "scene" && role != "memory"
        }
        if (lastRoleIndex < 0) throw IllegalStateException("当前会话还没有可修正的回复")
        val target = transcript[lastRoleIndex]
        val targetTurnId = target["turn_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val speaker = target["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val participants = (session["participants"]?.jsonArray ?: JsonArray(emptyList()))
            .mapNotNull { it.jsonPrimitive.contentOrNull }.filter(String::isNotBlank)
        val client = requireNotNull(llm) { "LLM 客户端未配置" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置" }

        // 原始用户输入（对齐 Python：取 target turn 的 user 条目）
        val userEntry = if (targetTurnId.isNotEmpty()) {
            transcript.lastOrNull { it["turn_id"]?.jsonPrimitive?.contentOrNull == targetTurnId && it["role"]?.jsonPrimitive?.contentOrNull == "user" }
        } else {
            null
        }
        val originalMessage = userEntry?.get("message")?.jsonPrimitive?.contentOrNull?.trim()
            ?: session["last_entry_preview"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        // 一致性审校问题（对齐 Python create_correction_branch：从 consistency_monitor.latest.issues 取）
        val monitor = session["consistency_monitor"]?.jsonObject ?: JsonObject(emptyMap())
        val latestReview = monitor["latest"]?.jsonObject ?: JsonObject(emptyMap())
        val issues = latestReview["issues"]?.jsonArray?.mapNotNull { it.jsonObject }.orEmpty()
        val correctionContext = buildJsonObject {
            put("message", originalMessage)
            put("message_kind", "dialogue")
            if (targetTurnId.isNotEmpty()) put("turn_id", targetTurnId)
            put(
                "issues",
                buildJsonArray {
                    issues.forEach { add(it) }
                },
            )
            put(
                "original_responses",
                buildJsonArray {
                    transcript.filter {
                        it["turn_id"]?.jsonPrimitive?.contentOrNull == targetTurnId &&
                            it["role"]?.jsonPrimitive?.contentOrNull != "user"
                    }.forEach { add(it) }
                },
            )
        }

        // 走完整 reply 管道（对齐 Python _generate_dialogue_responses），注入 CORRECTION_CONTEXT
        val payloadBuilder = DialoguePayloadBuilder(storage)
        val promptBuilder = DialoguePromptBuilder(loader)
        val turnId = "correct-" + randomUuid().replace("-", "").take(10)
        val payload = payloadBuilder.buildTurnPayload(
            runManifest = runManifest,
            session = session,
            turnId = turnId,
            message = originalMessage,
        ).toMutableMap()
        payload["correction_context"] = correctionContext.mapValues { (_, value) -> jsonValueToAny(value) }
        val messages = promptBuilder.buildDialogueLlmMessages(payload = payload, retryOnEmpty = true)
        val responseLimit = ((payload["host_action"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?.get("response_limit_hint") as? Number)?.toInt() ?: 2
        val maxTokens = DialogueService.resolveDialogueMaxTokens(
            responseLimitHint = responseLimit,
            reasoningEffort = storage.loadModelSettings()["reasoning_effort"] as? String ?: "auto",
        )
        val corrected = client.chatCompletion(
            messages = messages,
            temperature = 0.7,
            maxTokens = maxTokens,
            requireJsonObject = true,
        ).choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (corrected.isBlank()) throw IllegalStateException("修正结果为空")
        val forbidden = listOf(
            session["controlled_character"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            "User",
            "我",
        ).filter(String::isNotBlank)
        val correctedResponses = DialogueResponseParser.parse(
            content = corrected,
            allowedSpeakers = participants + listOf("旁白", "场景提示"),
            forbiddenSpeakers = forbidden,
        )
        if (correctedResponses.isEmpty()) throw IllegalStateException("修正结果为空")
        val timestamp = nowIsoString()
        val updatedTranscript = transcript.toMutableList().apply {
            // 删除 target turn 的全部角色回复（保留 user 条目），再插入修正后的回复
            removeAll { entry ->
                val role = entry["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
                targetTurnId.isNotEmpty() && entry["turn_id"]?.jsonPrimitive?.contentOrNull == targetTurnId &&
                    role != "user"
            }
            correctedResponses.forEach { response ->
                add(
                    buildJsonObject {
                        put("speaker", response.speaker)
                        put("message", response.message)
                        response.innerThought?.let { put("inner_thought", it) }
                        put(
                            "role",
                            if (response.speaker in setOf("旁白", "场景提示")) "scene" else "character",
                        )
                        put("turn_id", targetTurnId.ifEmpty { turnId })
                        put("timestamp", timestamp)
                        put("corrected_at", timestamp)
                    },
                )
            }
        }
        val correctedText = correctedResponses.lastOrNull()?.message.orEmpty()
        val updated = saveSession(runId, sessionId, session, extra = buildJsonObject {
            put("transcript", buildJsonArray { updatedTranscript.forEach(::add) })
            put("last_entry_preview", correctedText)
        })
        // 修正后重推 scene progress（对齐 Python _refresh_dialogue_scene_progress use_llm=False）
        val transcriptMaps = updatedTranscript.mapNotNull { entry ->
            runCatching {
                entry.mapValues { (_, value) ->
                    if (value is JsonObject) value else value.jsonPrimitive.contentOrNull
                }
            }.getOrNull()
        }
        val state = SceneProgressState.deriveSceneProgressState(updated, transcriptMaps, updatedAt = timestamp)
        val finalSession = saveSession(runId, sessionId, updated, extra = buildJsonObject {
            put("state", SceneProgressState.stateToJsonObject(state))
        })
        val effectiveTurnId = targetTurnId.ifEmpty { turnId }
        longTermMemory.replaceTurn(
            runId = runId,
            sessionId = sessionId,
            turnId = effectiveTurnId,
            message = originalMessage,
            responses = correctedResponses.map { response ->
                mapOf("speaker" to response.speaker, "message" to response.message)
            },
        )
        return finalSession
    }

    /**
     * 深度审校最新回复：调用 LLM 生成一致性审校结果并写入 consistency_monitor。
     * 对应 Python: deep_review_latest_dialogue_turn
     */
    suspend fun deepReview(runId: String, sessionId: String): JsonObject {
        val session = requireSession(runId, sessionId)
        val client = requireNotNull(llm) { "LLM 客户端未配置" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置" }
        val runManifest = storage.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        // 从 transcript 提取最近的角色回复作为审校对象（对齐 Python build_consistency_review_payload 的 responses）
        val transcript = transcriptOf(session)
        val responses = transcript.filter {
            val role = it["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
            role != "user" && role != "scene" && role != "memory"
        }.takeLast(8).map { entry ->
            mapOf<String, Any?>(
                "speaker" to (entry["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                "message" to (entry["message"]?.jsonPrimitive?.contentOrNull.orEmpty()),
            )
        }
        val payload = DialoguePayloadBuilder(storage).buildConsistencyReviewPayload(
            runManifest = runManifest,
            session = session,
            responses = responses,
        )
        val messages = DialoguePromptBuilder(loader).buildDialogueConsistencyReviewMessages(payload)
        val content = client.chatCompletion(
            messages = messages,
            temperature = 0.3,
            maxTokens = 1200,
            requireJsonObject = true,
        ).choices.firstOrNull()?.message?.content?.trim().orEmpty()
        val review = parseJsonObject(content)
        val timestamp = nowIsoString()
        val previous = session["consistency_monitor"]?.jsonObject ?: JsonObject(emptyMap())
        val history = (previous["history"]?.jsonArray ?: JsonArray(emptyList())).toMutableList()
        previous["latest"]?.let { latest -> history.add(0, latest) }
        val monitor = buildJsonObject {
            previous.forEach { (key, value) -> put(key, value) }
            put("latest", review)
            put("history", buildJsonArray { history.take(20).forEach(::add) })
            put("reviewed_at", timestamp)
        }
        return saveSession(runId, sessionId, session, extra = buildJsonObject {
            put("consistency_monitor", monitor)
        })
    }

    /**
     * 剧情导演：生成若干推进方案。
     * 对应 Python: direct_dialogue_turn
     */
    suspend fun directDialogue(
        runId: String,
        sessionId: String,
        goal: String,
        action: String,
        optionCount: Int,
    ): JsonObject {
        require(goal.isNotBlank() && goal.length <= 240) { "goal 需为 1-240 字" }
        val normalizedAction = action.trim().lowercase().ifBlank { "advance" }
        val session = requireSession(runId, sessionId)
        val count = optionCount.coerceIn(2, 4)
        val client = requireNotNull(llm) { "LLM 客户端未配置" }
        val loader = requireNotNull(prompts) { "提示词加载器未配置" }
        val runManifest = storage.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val payload = DialoguePayloadBuilder(storage).buildDirectorPayload(
            runManifest = runManifest,
            session = session,
            goal = goal,
            action = normalizedAction,
            optionCount = count,
        )
        val messages = DialoguePromptBuilder(loader).buildDialogueDirectorLlmMessages(
            payload = payload,
            retryOnEmpty = false,
        )
        val content = client.chatCompletion(
            messages = messages,
            temperature = 0.9,
            maxTokens = 1600,
            requireJsonObject = true,
        ).choices.firstOrNull()?.message?.content?.trim().orEmpty()
        val parsed = parseJsonObject(content)
        val options = parsed["options"]?.jsonArray ?: return buildJsonObject { put("options", JsonArray(emptyList())) }
        return buildJsonObject {
            put("message_kind", if (normalizedAction == "fourth_wall") "fourth_wall" else "plot")
            put("options", buildJsonArray { options.take(count).forEach(::add) })
        }
    }

    // ------------------------------------------------------------------
    // 场景卡
    // ------------------------------------------------------------------

    /**
     * 切换场景卡。
     * 对应 Python: switch_dialogue_scene_card
     */
    fun switchScene(
        runId: String,
        sessionId: String,
        sceneCardId: String,
        sceneProfile: JsonObject,
        transitionMessage: String,
        autoContinue: Boolean,
    ): JsonObject {
        val session = requireSession(runId, sessionId)
        val timestamp = nowIsoString()
        val sceneCard = buildJsonObject {
            if (sceneCardId.isNotBlank()) put("card_id", sceneCardId)
            put("fields", sceneProfile)
            put("preview", buildJsonObject {
                sceneProfile["title"]?.let { put("title", it) }
                sceneProfile["location"]?.let { put("location", it) }
                sceneProfile["time_hint"]?.let { put("time_hint", it) }
            })
        }
        val sceneHistory = (session["scene_history"]?.jsonArray ?: JsonArray(emptyList())).toMutableList()
        if (transitionMessage.isNotBlank()) {
            sceneHistory.add(
                buildJsonObject {
                    put("scene_card_id", sceneCardId)
                    put("transition_message", transitionMessage)
                    put("switched_at", timestamp)
                },
            )
        }
        val updated = saveSession(runId, sessionId, session, extra = buildJsonObject {
            put("scene_card_id", sceneCardId)
            put("scene_card", sceneCard)
            put("scene_history", buildJsonArray { sceneHistory.forEach(::add) })
        })
        val final = if (autoContinue && transitionMessage.isNotBlank()) {
            // auto_continue：将转场消息作为场景提示写入 transcript
            val transcript = transcriptOf(updated).toMutableList()
            transcript.add(
                buildJsonObject {
                    put("speaker", "场景提示")
                    put("message", transitionMessage)
                    put("role", "scene")
                    put("turn_id", "scene-" + randomUuid().replace("-", "").take(10))
                    put("timestamp", timestamp)
                },
            )
            saveSession(runId, sessionId, updated, extra = buildJsonObject {
                put("transcript", buildJsonArray { transcript.forEach(::add) })
            })
        } else {
            updated
        }
        // 对齐 Python switch_scene_card：写入场景切换事件信号并推导 scene progress state
        val sceneProfileMap = sceneProfile.mapValues { (_, value) ->
            value.jsonPrimitive.contentOrNull
        }
        val state = SceneProgressState.deriveAfterSceneSwitch(
            session = final,
            sceneProfile = sceneProfileMap,
            transitionMessage = transitionMessage,
            switchedAt = timestamp,
        )
        return saveSession(runId, sessionId, final, extra = buildJsonObject {
            put("state", SceneProgressState.stateToJsonObject(state))
        })
    }

    /**
     * 推荐场景卡（规则引擎，不调 LLM）。
     * 对应 Python: recommend_dialogue_scene_card
     */
    fun recommendScene(runId: String, sessionId: String): JsonObject {
        val session = requireSession(runId, sessionId)
        val cardsDir = storage.getStorageRoot() / "scene-cards"
        val candidates = mutableListOf<Pair<String, JsonObject>>()
        if (storage.exists(cardsDir)) {
            storage.listFiles(cardsDir).filter { storage.isDirectory(it) }.forEach { dir ->
                val cardFile = dir / "scene-card.json"
                if (storage.exists(cardFile)) {
                    runCatching {
                        val fields = json.parseToJsonElement(storage.readText(cardFile)).jsonObject
                        candidates.add(dir.name to fields)
                    }
                }
            }
        }
        val participants = session["participants"]?.jsonArray?.mapNotNull {
            it.jsonPrimitive.contentOrNull
        }.orEmpty()
        val mode = session["mode"]?.jsonPrimitive?.contentOrNull ?: "observe"
        val currentId = session["scene_card_id"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val scored = candidates.mapNotNull { (id, fields) ->
            if (id == currentId) return@mapNotNull null
            var score = 0
            val text = buildString {
                fields.forEach { (_, value) -> value.jsonPrimitive.contentOrNull?.let { append(it) } }
            }
            if (fields.containsKey("scene_drive")) score += 3
            if (fields.containsKey("opening_situation")) score += 2
            if (fields.containsKey("atmosphere")) score += 1
            if (participants.size >= 3 && text.any { it in "厅堂席宴城" }) score += 2
            if (participants.size == 2 && text.contains("对坐")) score += 1
            if (mode == "insert" && text.contains("插入")) score += 1
            if (mode == "observe" && text.any { it in "试探摊牌转折" }) score += 1
            val preview = buildJsonObject {
                fields["title"]?.let { put("title", it) }
                fields["location"]?.let { put("location", it) }
                fields["time_hint"]?.let { put("time_hint", it) }
            }
            Triple(id, score, preview)
        }.sortedByDescending { it.second }

        val recommended = scored.firstOrNull()
        return buildJsonObject {
            put("recommended_card_id", recommended?.first.orEmpty())
            put("items", buildJsonArray {
                scored.forEach { (id, _, preview) ->
                    add(
                        buildJsonObject {
                            put("card_id", id)
                            put("fields", preview)
                        },
                    )
                }
            })
            put("mode", mode)
            put("participants", buildJsonArray { participants.forEach { add(JsonPrimitive(it)) } })
            put("recommended_auto_continue_message", "")
        }
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private fun requireSession(runId: String, sessionId: String): JsonObject {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val session = runCatching { storage.getDialogueSession(runId, sessionId) }.getOrNull()
            ?: throw NoSuchElementException("Session not found: $sessionId")
        return session
    }

    private fun saveSession(
        runId: String,
        sessionId: String,
        session: JsonObject,
        extra: JsonObject = JsonObject(emptyMap()),
    ): JsonObject {
        val file = storage.getDialogueSessionManifestFile(runId, sessionId)
        val updated = buildJsonObject {
            session.forEach { (key, value) -> put(key, value) }
            extra.forEach { (key, value) -> put(key, value) }
            put("updated_at", nowIsoString())
        }
        storage.writeTextAtomically(file, json.encodeToString(JsonObject.serializer(), updated))
        return updated
    }

    private fun transcriptOf(session: JsonObject): List<JsonObject> =
        session["transcript"]?.jsonArray?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }.orEmpty()

    private fun jsonValueToAny(value: kotlinx.serialization.json.JsonElement?): Any? = when (value) {
        null -> null
        is JsonObject -> value.mapValues { jsonValueToAny(it.value) }
        is JsonArray -> value.map { jsonValueToAny(it) }
        is kotlinx.serialization.json.JsonPrimitive ->
            if (value.isString) value.content else value.contentOrNull ?: value.toString()
    }

    private fun memoryLedgerOf(session: JsonObject): List<JsonObject> =
        session["memory_ledger"]?.jsonArray?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }.orEmpty()

    /**
     * 创建分支会话：复制源会话并截断 transcript。
     */
    private fun forkSession(
        runId: String,
        source: JsonObject,
        sourceTurnId: String,
        keepFrom: Int,
    ): JsonObject {
        val transcript = transcriptOf(source)
        val kept = transcript.take(keepFrom)
        val branchId = "sess-" + randomUuid().replace("-", "").take(12)
        val timestamp = nowIsoString()
        val branchOrigin = buildJsonObject {
            put("source_session_id", source["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
            if (sourceTurnId.isNotBlank()) put("source_turn_id", sourceTurnId)
            put("created_at", timestamp)
            put("fork_type", "branch")
        }
        val parentGraph = source["branch_graph"]?.jsonObject ?: JsonObject(emptyMap())
        val graph = buildJsonObject {
            parentGraph.forEach { (key, value) -> put(key, value) }
            put(
                branchId,
                buildJsonObject {
                    put("parent_id", source["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("source_turn_id", sourceTurnId)
                    put("created_at", timestamp)
                },
            )
        }
        val branch = buildJsonObject {
            source.forEach { (key, value) -> put(key, value) }
            put("session_id", branchId)
            put("title", (source["title"]?.jsonPrimitive?.contentOrNull ?: "").ifBlank { "分支会话" })
            put("created_at", timestamp)
            put("updated_at", timestamp)
            put("transcript", buildJsonArray { kept.forEach(::add) })
            put("branch_origin", branchOrigin)
            put("branch_graph", graph)
            put("branch_meta", buildJsonObject {
                put("label", "分支")
                put("is_mainline", false)
            })
            put("status", "ready")
            put("turn_count", kept.count { item ->
                val role = item["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
                role != "scene"
            })
            put("current_turn_id", kept.lastOrNull()?.get("turn_id")?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        val retainedTurnIds = kept.mapNotNull {
            it["turn_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        }.toSet()
        val branchForState = buildJsonObject {
            branch.forEach { (key, value) -> put(key, value) }
            // Re-derive state from the retained transcript; the parent's final state may contain future facts.
            put("state", JsonObject(emptyMap()))
        }
        val retainedTranscript = kept.mapNotNull { item ->
            runCatching {
                item.jsonObject.mapValues { (_, value) ->
                    when (value) {
                        is JsonObject -> value
                        else -> value.jsonPrimitive.contentOrNull
                    }
                }
            }.getOrNull()
        }
        val branchState = SceneProgressState.deriveSceneProgressState(
            session = branchForState,
            transcript = retainedTranscript,
            updatedAt = timestamp,
        )
        val branchWithState = buildJsonObject {
            branch.forEach { (key, value) -> put(key, value) }
            put("state", SceneProgressState.stateToJsonObject(branchState))
            branch["consistency_monitor"]?.jsonObject?.let { monitor ->
                put("consistency_monitor", buildJsonObject {
                    monitor.forEach { (key, value) -> put(key, value) }
                    val ledger = monitor["knowledge_ledger"]?.jsonArray.orEmpty()
                    put("knowledge_ledger", buildJsonArray {
                        ledger.forEach { item ->
                            val entry = runCatching { item.jsonObject }.getOrNull()
                            val entryTurnId = entry?.get("turn_id")?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (entryTurnId.isBlank() || entryTurnId in retainedTurnIds) add(item)
                        }
                    })
                })
            }
        }
        val dir = storage.getDialogueSessionsDirectory(runId) / branchId
        storage.mkdirs(dir)
        val file = dir / "session_manifest.json"
        storage.writeTextAtomically(file, json.encodeToString(JsonObject.serializer(), branchWithState))
        longTermMemory.copyForBranch(
            runId = runId,
            sourceSessionId = source["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            targetSessionId = branchId,
            retainedTurnIds = retainedTurnIds,
        )
        return branchWithState
    }

    private fun markSessionRecovered(session: JsonObject): JsonObject {
        // Ktor 后端无挂起操作，恢复仅刷新恢复标记
        return buildJsonObject {
            session.forEach { (key, value) -> put(key, value) }
            put("recovered_at", nowIsoString())
            put("status", "ready")
        }
    }

    private fun parseJsonObject(content: String): JsonObject {
        val normalized = stripCodeFences(content)
        return try {
            json.parseToJsonElement(normalized).jsonObject
        } catch (error: Exception) {
            PlatformLog.e(TAG, "LLM returned non-JSON content: $content", error)
            JsonObject(emptyMap())
        }
    }
}

package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.ktor.models.DialogueResponse
import top.wkbin.zaomeng.ktor.models.DialogueTurnResponse
import top.wkbin.zaomeng.data.api.OriginalKnowledgeEntryDto
import top.wkbin.zaomeng.data.api.OriginalKnowledgeLocationDto
import okio.Path
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.SimpleLock
import top.wkbin.zaomeng.platform.nowEpochMillis
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.platformIoDispatcher
import top.wkbin.zaomeng.platform.randomUuid

/**
 * Dialogue service for managing conversation turns.
 *
 * This is a simplified implementation for Phase 3.4.
 * It demonstrates the integration of LLM client and prompt loading.
 */
class DialogueService(
    private val storageService: StorageService,
    private val llmClient: LlmClient? = null,
    private val promptLoader: PromptLoader? = null,
    private val worldMemory: WorldMemoryService? = null,
    private val pluginRuleEngine: PluginRuleEngine? = null,
) {
    private val longTermMemory = LongTermMemoryService(storageService)
    private val originalKnowledge = OriginalKnowledgeService(storageService)
    companion object {
        private const val TAG = "DialogueService"

        // 对齐 Python helpers.py:DIALOGUE_RESPONSE_MIN/MAX_MAX_TOKENS：
        // 推理模型会把 reasoning_content 计入输出预算，默认给足一轮预算避免重复推理
        const val DIALOGUE_RESPONSE_MIN_MAX_TOKENS = 8192
        const val DIALOGUE_RESPONSE_MAX_MAX_TOKENS = 16000
        const val DIALOGUE_RESPONSE_NON_REASONING_MIN_MAX_TOKENS = 1200
        const val DIALOGUE_RESPONSE_NON_REASONING_MAX_MAX_TOKENS = 4096

        /**
         * 解析对话回复的 max_tokens（对齐 Python _resolve_dialogue_max_tokens 的无配置分支）。
         */
        fun resolveDialogueMaxTokens(responseLimitHint: Int, reasoningEffort: String = "auto"): Int {
            val limit = responseLimitHint
            if (reasoningEffort.trim().equals("off", ignoreCase = true)) {
                return if (limit > 0) {
                    minOf(
                        maxOf(DIALOGUE_RESPONSE_NON_REASONING_MIN_MAX_TOKENS, 520 + limit * 360),
                        DIALOGUE_RESPONSE_NON_REASONING_MAX_MAX_TOKENS,
                    )
                } else {
                    DIALOGUE_RESPONSE_NON_REASONING_MIN_MAX_TOKENS
                }
            }
            return if (limit > 0) {
                minOf(
                    maxOf(DIALOGUE_RESPONSE_MIN_MAX_TOKENS, 520 + limit * 360),
                    DIALOGUE_RESPONSE_MAX_MAX_TOKENS,
                )
            } else {
                DIALOGUE_RESPONSE_MIN_MAX_TOKENS
            }
        }

        /** 角色读心增强器（inner-thoughts）是否在会话内启用（读 session 的 plugin_enhancer_states）。 */
        fun isInnerThoughtsEnhancerActive(session: JsonObject): Boolean {
            val states = session["plugin_enhancer_states"]?.jsonObject ?: return false
            val pluginStates = states["com.zaomeng.inner-thoughts"]?.jsonObject ?: return false
            return pluginStates["inner-thoughts"]?.jsonPrimitive?.contentOrNull == "true" ||
                pluginStates["inner-thoughts"]?.jsonPrimitive?.booleanOrNull == true
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val backgroundScope = CoroutineScope(SupervisorJob() + platformIoDispatcher)
    private val backgroundJobs = HashMap<String, Job>()
    private val backgroundPending = HashMap<String, MutableList<PostTurnWork>>()
    private val backgroundJobsLock = SimpleLock()
    private val sessionMutationLocks = HashMap<String, SimpleLock>()
    private val sessionMutationLocksLock = SimpleLock()

    private fun sessionMutationLock(runId: String, sessionId: String): SimpleLock =
        sessionMutationLocksLock.withLock {
            sessionMutationLocks.getOrPut("$runId:$sessionId") { SimpleLock() }
        }

    private data class PostTurnWork(
        val turnId: String,
        val message: String,
        val responses: List<DialogueResponse>,
    )

    @Serializable
    private data class DialogueManifest(
        @SerialName("session_id")
        val sessionId: String,
        @SerialName("run_id")
        val runId: String = "",
        val title: String,
        val mode: String,
        val participants: List<String> = emptyList(),
        @SerialName("controlled_character")
        val controlledCharacter: String? = null,
        @SerialName("turn_count")
        val turnCount: Int = 0,
        @SerialName("created_at")
        val createdAt: String = "",
        @SerialName("updated_at")
        val updatedAt: String = ""
    )

    @Serializable
    private data class TurnRecord(
        @SerialName("turn_id")
        val turnId: String,
        val message: String,
        @SerialName("message_kind")
        val messageKind: String,
        @SerialName("input_speaker")
        val inputSpeaker: String = "",
        val responses: List<DialogueResponse>,
        val timestamp: Long,
        val evidence: List<OriginalKnowledgeEntryDto> = emptyList(),
    )

    /**
     * Reply to a dialogue turn (non-streaming).
     *
     * This is a simplified implementation that:
     * 1. Loads the prompt template
     * 2. Calls the LLM API
     * 3. Saves the turn to storage
     *
     * @param runId The run ID
     * @param sessionId The dialogue session ID
     * @param message User input message
     * @param messageKind Type of message (user_input, narration, etc.)
     * @param includeInnerThoughts Whether to include character inner thoughts
     * @return The dialogue turn response
     */
    suspend fun replyDialogueTurn(
        runId: String,
        sessionId: String,
        message: String,
        messageKind: String = "user_input",
        speakerOverride: String = "",
        suppressTranscriptMessage: Boolean = false,
        includeInnerThoughts: Boolean = false,
        operationId: String = "",
    ): DialogueTurnResponse {
        PlatformLog.d(TAG, "Replying to dialogue turn: run=$runId, session=$sessionId")

        // 1. Load session manifest（一次读取；DTO 与 JsonObject 复用同一份，避免重复读文件）
        val turnId = operationId.ifBlank { randomUuid() }
        val sessionManifestJson = prepareSessionForGeneration(runId, sessionId, turnId, message)
        val sessionManifest = json.decodeFromJsonElement<DialogueManifest>(sessionManifestJson)
        val participants = sessionManifest.participants

        // 2. Load model settings
        val modelSettings = storageService.loadModelSettings()
        val modelName = modelSettings["model"] as? String ?: "gpt-4"

        // 3. Build prompt（对齐 Python build_dialogue_llm_messages：
        //    stable system + turn system + JSON user payload，含 memory/persona/relation/history 上下文）
        val promptLoader = promptLoader
            ?: throw IllegalStateException("LLM not configured (prompt loader unavailable)")
        val payloadBuilder = DialoguePayloadBuilder(storageService)
        val promptBuilder = DialoguePromptBuilder(promptLoader)
        val runManifest = storageService.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val payload = payloadBuilder.buildTurnPayload(
            runManifest = runManifest,
            session = sessionManifestJson,
            turnId = turnId,
            message = message,
            messageKind = messageKind,
            speakerOverride = speakerOverride,
            includeInnerThoughts = includeInnerThoughts || DialogueService.isInnerThoughtsEnhancerActive(sessionManifestJson),
        )
        val conversationHistory = promptBuilder.buildDialogueLlmMessages(
            payload = payload,
            retryOnEmpty = false,
        )

        // 4. 计算 max_tokens（对齐 Python _resolve_dialogue_max_tokens：
        //    推理模型会把 reasoning_content 计入输出预算，默认至少 8192，上限 16000）
        val responseLimit = ((payload["host_action"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?.get("response_limit_hint") as? Number)?.toInt() ?: 0
        val maxTokens = resolveDialogueMaxTokens(
            responseLimitHint = responseLimit,
            reasoningEffort = modelSettings["reasoning_effort"] as? String ?: "auto",
        )

        // 5. 调用 LLM API（对齐 Python generate_dialogue_responses：解析失败重试一次，maxTokens 翻倍 + retryOnEmpty 提示词）
        val llmClient = llmClient
            ?: throw IllegalStateException("LLM not configured (client unavailable)")
        val forbiddenSpeakers = listOf(
            sessionManifest.controlledCharacter.orEmpty().takeIf { sessionManifest.mode == "act" }.orEmpty(),
            (payload["input"] as? Map<*, *>)?.mapKeys { it.key.toString() }?.get("speaker")?.toString().orEmpty(),
        )
        var responses: List<DialogueResponse>? = null
        for (attempt in 0..1) {
            val retry = attempt > 0
            try {
                val history = if (retry) {
                    promptBuilder.buildDialogueLlmMessages(payload, retryOnEmpty = true)
                } else {
                    conversationHistory
                }
                val llmResponse = llmClient.chatCompletion(
                    messages = history,
                    model = modelName,
                    temperature = 0.7,
                    maxTokens = if (retry) minOf(maxTokens * 2, DIALOGUE_RESPONSE_MAX_MAX_TOKENS) else maxTokens,
                    requireJsonObject = false,
                )
                val responseContent = llmResponse.choices.firstOrNull()?.message?.content
                    ?: throw IllegalArgumentException("Empty response from LLM")
                val allowedResponders = ((payload["input"] as? Map<*, *>)
                    ?.mapKeys { it.key.toString() }
                    ?.get("allowed_responders") as? List<*>)
                    ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
                    .orEmpty()
                responses = parseDialogueResponses(
                    responseContent,
                    allowedResponders,
                    forbiddenSpeakers = forbiddenSpeakers,
                )
                break
            } catch (e: IllegalArgumentException) {
                PlatformLog.w(TAG, "Dialogue response parse failed (attempt ${attempt + 1}): ${e.message}")
                if (attempt == 1) throw e
            }
        }
        val finalResponses = responses
            ?: throw IllegalStateException("No dialogue responses generated")

        // 6. Save turn + update manifest；operation_id 来自请求体，需防路径穿越（与 prepareDialogueTurn 一致）
        if (turnId.contains('/') || turnId.contains('\\') || turnId.contains("..")) {
            throw IllegalArgumentException("Invalid operation_id")
        }
        commitTurn(
            runId = runId,
            sessionId = sessionId,
            turnId = turnId,
            message = message,
            messageKind = messageKind,
            inputSpeaker = (payload["input"] as? Map<*, *>)
                ?.mapKeys { it.key.toString() }
                ?.get("speaker")
                ?.toString()
                .orEmpty(),
            inputRole = if (speakerOverride.isNotBlank()) "character" else "user",
            responses = finalResponses,
            suppressTranscriptMessage = suppressTranscriptMessage,
            existingSession = sessionManifestJson,
            evidence = extractOriginalEvidence(payload),
        )

        return DialogueTurnResponse(
            turnId = turnId,
            response = finalResponses.firstOrNull() ?: DialogueResponse(
                speaker = "",
                message = "",
                innerThought = null
            )
        )
    }

    internal fun prepareSessionForGeneration(
        runId: String,
        sessionId: String,
        turnId: String,
        message: String,
    ): JsonObject {
        val stored = storageService.loadSessionManifest(runId, sessionId)
        return pluginRuleEngine?.beforeGeneration(runId, sessionId, turnId, message, stored) ?: stored
    }

    /**
     * Load session manifest.
     */
    private fun loadSessionManifest(runId: String, sessionId: String): DialogueManifest {
        val manifestFile = storageService.getDialogueSessionManifestFile(runId, sessionId)

        if (!storageService.exists(manifestFile)) {
            throw NoSuchElementException("Session not found: $sessionId")
        }

        return json.decodeFromString(storageService.readText(manifestFile))
    }

    /**
     * Parse LLM response into dialogue responses.
     *
     * 解析 NDJSON 对话对象，并兼容旧 JSON 数组/包装对象，
     * 允许旁白/场景提示，禁止受控角色与用户身份；无有效回复时抛异常（不 fallback 原文）。
     */
    private fun parseDialogueResponses(
        content: String,
        participants: List<String>,
        forbiddenSpeakers: List<String> = emptyList(),
    ): List<DialogueResponse> {
        val allowed = participants + listOf("旁白", "场景提示")
        return DialogueResponseParser.parse(content, allowed, forbiddenSpeakers)
    }

    /** 对话后台可能基于旧快照计算派生状态；落盘时始终保留插件刚写入的会话控制字段。 */
    private fun persistSessionManifestPreservingControls(
        runId: String,
        sessionId: String,
        candidate: JsonObject,
    ): JsonObject = storageService.updateSessionManifest(runId, sessionId) { latest ->
        buildJsonObject {
            candidate.forEach { (key, value) -> put(key, value) }
            latest["muted_characters"]?.let { put("muted_characters", it) }
        }
    }

    /**
     * 提交一轮对话结果：保存 turn 文件并更新会话 manifest（非流式与流式共用）。
     */
    suspend fun commitTurn(
        runId: String,
        sessionId: String,
        turnId: String,
        message: String,
        messageKind: String,
        inputSpeaker: String,
        inputRole: String = "user",
        responses: List<DialogueResponse>,
        suppressTranscriptMessage: Boolean = false,
        evidence: List<OriginalKnowledgeEntryDto> = emptyList(),
        // Kept for call-site compatibility. Commit still reloads under the mutation lock so
        // background enrichment cannot be overwritten; StorageService reuses the parsed
        // manifest when the document has not changed.
        existingSession: JsonObject? = null,
    ): JsonObject {
        // The pre-generation snapshot may already be stale because the previous turn's
        // enrichment runs in the background. Reload and persist under one session lock.
        val updated = sessionMutationLock(runId, sessionId).withLock {
            val sessionManifest = storageService.loadSessionManifest(runId, sessionId)
            saveTurn(runId, sessionId, turnId, message, messageKind, inputSpeaker, responses, evidence)
            val committed = updateSessionManifest(
                runId = runId,
                sessionId = sessionId,
                newTurnCount = (sessionManifest["turn_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) + 1,
                turnId = turnId,
                userMessage = message,
                inputSpeaker = inputSpeaker,
                inputRole = inputRole,
                suppressUserMessage = suppressTranscriptMessage,
                messageKind = messageKind,
                responses = responses,
                evidence = evidence,
                mode = sessionManifest["mode"]?.jsonPrimitive?.contentOrNull ?: "observe",
                manifest = sessionManifest,
                deriveState = false,
            )
            pluginRuleEngine?.afterTurn(runId, sessionId, turnId, message, responses)
            if (pluginRuleEngine == null) committed else storageService.loadSessionManifest(runId, sessionId)
        }
        enqueueBackgroundPostTurn(
            runId = runId,
            sessionId = sessionId,
            turnId = turnId,
            message = message,
            responses = responses,
        )
        return updated
    }

    /** Run derived memory/state work off the response path and coalesce bursts per session. */
    private fun enqueueBackgroundPostTurn(
        runId: String,
        sessionId: String,
        turnId: String,
        message: String,
        responses: List<DialogueResponse>,
    ) {
        val key = "$runId:$sessionId"
        backgroundJobsLock.withLock {
            backgroundPending.getOrPut(key) { mutableListOf() }
                .add(PostTurnWork(turnId = turnId, message = message, responses = responses))
            if (backgroundJobs[key]?.isActive == true) return@withLock
            backgroundJobs[key] = backgroundScope.launch {
                drainBackgroundPostTurns(key, runId, sessionId)
            }
        }
    }

    private suspend fun drainBackgroundPostTurns(key: String, runId: String, sessionId: String) {
        while (true) {
            val batch = backgroundJobsLock.withLock {
                val pending = backgroundPending.remove(key)?.toList().orEmpty()
                if (pending.isEmpty()) backgroundJobs.remove(key)
                pending
            }
            if (batch.isEmpty()) return
            val startedAt = nowEpochMillis()
            val derivedSession = runCatching {
                sessionMutationLock(runId, sessionId).withLock {
                    var manifest = storageService.loadSessionManifest(runId, sessionId)
                    batch.forEach { work ->
                        manifest = updateKnowledgeLedger(
                            session = manifest,
                            turnId = work.turnId,
                            message = work.message,
                            responses = work.responses,
                        )
                    }
                    refreshDerivedSessionState(runId, sessionId, manifest)
                }
            }.onFailure { error ->
                PlatformLog.w(TAG, "Background scene/ledger update failed for $runId/$sessionId: ${error.message}", error)
            }.getOrNull()

            if (derivedSession != null) {
                batch.forEach { work -> syncWorldMemory(runId, sessionId, work, derivedSession) }
            }
            batch.forEach { work ->
                runCatching {
                    longTermMemory.appendTurn(
                        runId = runId,
                        sessionId = sessionId,
                        turnId = work.turnId,
                        message = work.message,
                        responses = work.responses.map { response ->
                            mapOf("speaker" to response.speaker, "message" to response.message)
                        },
                    )
                }.onFailure { error ->
                    PlatformLog.w(TAG, "Background long-term memory update failed for $runId/$sessionId: ${error.message}", error)
                }
            }
            runCatching {
                storageService.readRunManifest(runId)?.let { runManifest ->
                    val participants = (derivedSession?.get("participants")?.jsonArray ?: JsonArray(emptyList()))
                        .mapNotNull { it.jsonPrimitive.contentOrNull }
                    originalKnowledge.ensure(
                        runManifest = runManifest,
                        characterNames = participants + batch.flatMap { it.responses }.map { it.speaker },
                    )
                }
            }.onFailure { error ->
                PlatformLog.w(TAG, "Background original-source index update failed for $runId: ${error.message}", error)
            }
            PlatformLog.d(
                TAG,
                "Background post-turn batch completed: run=$runId, session=$sessionId, " +
                    "turns=${batch.size}, elapsed_ms=${nowEpochMillis() - startedAt}",
            )
        }
    }

    private fun updateKnowledgeLedger(
        session: JsonObject,
        turnId: String,
        message: String,
        responses: List<DialogueResponse>,
    ): JsonObject {
        val participants = (session["participants"]?.jsonArray ?: JsonArray(emptyList()))
            .mapNotNull { it.jsonPrimitive.contentOrNull }.map(String::trim).filter(String::isNotBlank).distinct()
        val scenePresentParticipants = session["scene_progress"]?.jsonObject
            ?.get("present_participants")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter(String::isNotBlank)
            ?.distinct()
            .orEmpty()
        val statePresentParticipants = session["state"]?.jsonObject
            ?.get("presence")?.jsonObject
            ?.get("present_participants")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter(String::isNotBlank)
            ?.distinct()
            .orEmpty()
        val holdersBase = (scenePresentParticipants.ifEmpty { statePresentParticipants }).ifEmpty { participants }
        val entries = buildList {
            add("User" to message)
            responses.forEach { add(it.speaker to it.message) }
        }
        val ledger = (session["consistency_monitor"]?.jsonObject?.get("knowledge_ledger")?.jsonArray
            ?: JsonArray(emptyList())).mapNotNull { runCatching { it.jsonObject }.getOrNull() }.toMutableList()
        val now = nowIsoString()
        val patterns = listOf(
            Regex("(?:秘密|真相|实情|底牌)(?:是|为|：|:)\\s*([^。！？!?\\n]{4,80})"),
            Regex("(?:只告诉你|别告诉别人|不要告诉别人)[，,:：]?\\s*([^。！？!?\\n]{4,80})"),
        )
        entries.forEach { (speaker, text) ->
            patterns.flatMap { pattern -> pattern.findAll(text).map { it.groupValues[1].trim() }.toList() }
                .distinct().forEach { fact ->
                    val index = ledger.indexOfFirst {
                        it["fact"]?.jsonPrimitive?.contentOrNull.orEmpty().replace(Regex("\\s+"), "") ==
                            fact.replace(Regex("\\s+"), "")
                    }
                    val holders = (holdersBase + speaker).filter(String::isNotBlank).distinct()
                    if (index >= 0) {
                        ledger[index] = buildJsonObject {
                            ledger[index].forEach { (key, value) -> put(key, value) }
                            put("holders", buildJsonArray {
                                ((ledger[index]["holders"]?.jsonArray ?: JsonArray(emptyList()))
                                    .mapNotNull { it.jsonPrimitive.contentOrNull } + holders)
                                    .distinct().forEach { add(JsonPrimitive(it)) }
                            })
                            put("updated_at", now)
                        }
                    } else {
                        ledger += buildJsonObject {
                            put("fact", fact)
                            put("source", speaker)
                            put("turn_id", turnId)
                            put("holders", buildJsonArray { holders.forEach { add(JsonPrimitive(it)) } })
                            put("created_at", now)
                            put("updated_at", now)
                        }
                    }
                }
        }
        if (ledger.isEmpty()) return session
        val monitor = buildJsonObject {
            session["consistency_monitor"]?.jsonObject?.forEach { (key, value) -> put(key, value) }
            put("knowledge_ledger", buildJsonArray { ledger.takeLast(40).forEach(::add) })
        }
        return buildJsonObject {
            session.forEach { (key, value) -> put(key, value) }
            put("consistency_monitor", monitor)
        }
    }

    /**
     * Save a turn to storage.
     */
    private fun saveTurn(
        runId: String,
        sessionId: String,
        turnId: String,
        message: String,
        messageKind: String,
        inputSpeaker: String,
        responses: List<DialogueResponse>,
        evidence: List<OriginalKnowledgeEntryDto> = emptyList(),
    ) {
        val turnDir = storageService.getDialogueSessionsDirectory(runId) / "$sessionId/turns/$turnId"
        storageService.mkdirs(turnDir)

        val turnRecord = TurnRecord(
            turnId = turnId,
            message = message,
            messageKind = messageKind,
            inputSpeaker = inputSpeaker,
            responses = responses,
            timestamp = nowEpochMillis(),
            evidence = evidence,
        )

        val turnFile = turnDir / "turn.json"
        storageService.writeTextAtomically(turnFile, Json.encodeToString(TurnRecord.serializer(), turnRecord))
    }

    internal fun extractOriginalEvidence(payload: Map<String, Any?>): List<OriginalKnowledgeEntryDto> {
        val context = (payload["original_source_context"] as? Map<*, *>)
            ?.mapKeys { it.key.toString() }
            ?: return emptyList()
        return ((context["entries"] as? List<*>) ?: emptyList<Any?>()).mapNotNull { raw ->
            val item = (raw as? Map<*, *>)?.mapKeys { it.key.toString() } ?: return@mapNotNull null
            val sourceId = item["source_id"]?.toString()?.trim().orEmpty()
            val excerpt = item["excerpt"]?.toString()?.trim().orEmpty()
            if (sourceId.isBlank() || excerpt.isBlank()) return@mapNotNull null
            val location = (item["location"] as? Map<*, *>)?.mapKeys { it.key.toString() }.orEmpty()
            OriginalKnowledgeEntryDto(
                sourceId = sourceId,
                title = item["title"]?.toString()?.trim().orEmpty(),
                excerpt = excerpt,
                score = (item["score"] as? Number)?.toDouble() ?: 0.0,
                visibility = item["visibility"]?.toString().orEmpty().ifBlank { "uncertain" },
                knowers = stringList(item["knowers"]),
                characters = stringList(item["characters"]),
                allowedCharacters = stringList(item["allowed_characters"]),
                deniedCharacters = stringList(item["denied_characters"]),
                boundarySource = item["boundary_source"]?.toString().orEmpty().ifBlank { "automatic" },
                epistemicStatus = item["epistemic_status"]?.toString().orEmpty().ifBlank { "explicit_source" },
                pinned = item["pinned"] as? Boolean ?: false,
                location = OriginalKnowledgeLocationDto(
                    startChar = (location["start_char"] as? Number)?.toInt() ?: 0,
                    endChar = (location["end_char"] as? Number)?.toInt() ?: 0,
                ),
            )
        }.distinctBy(OriginalKnowledgeEntryDto::sourceId).take(3)
    }

    private fun evidenceToJson(evidence: List<OriginalKnowledgeEntryDto>): JsonArray = buildJsonArray {
        evidence.forEach { item ->
            add(buildJsonObject {
                put("source_id", item.sourceId)
                put("title", item.title)
                put("excerpt", item.excerpt)
                put("score", item.score)
                put("visibility", item.visibility)
                put("knowers", buildJsonArray { item.knowers.forEach { add(JsonPrimitive(it)) } })
                put("characters", buildJsonArray { item.characters.forEach { add(JsonPrimitive(it)) } })
                put("allowed_characters", buildJsonArray { item.allowedCharacters.forEach { add(JsonPrimitive(it)) } })
                put("denied_characters", buildJsonArray { item.deniedCharacters.forEach { add(JsonPrimitive(it)) } })
                put("boundary_source", item.boundarySource)
                put("epistemic_status", item.epistemicStatus)
                put("pinned", item.pinned)
                put("location", buildJsonObject {
                    put("start_char", item.location.startChar)
                    put("end_char", item.location.endChar)
                })
            })
        }
    }

    private fun stringList(value: Any?): List<String> =
        (value as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }?.distinct().orEmpty()

    /**
     * Update session manifest with new turn count.
     */
    private fun updateSessionManifest(
        runId: String,
        sessionId: String,
        newTurnCount: Int,
        turnId: String,
        userMessage: String,
        inputSpeaker: String,
        inputRole: String,
        suppressUserMessage: Boolean,
        messageKind: String,
        responses: List<DialogueResponse>,
        evidence: List<OriginalKnowledgeEntryDto>,
        mode: String = "observe",
        manifest: JsonObject,
        deriveState: Boolean = true,
    ): JsonObject {
        val timestamp = nowIsoString()
        val transcript = manifest["transcript"] as? JsonArray ?: JsonArray(emptyList())
        val combinedTranscript = buildJsonArray {
            transcript.forEach(::add)
            if (!suppressUserMessage) add(buildJsonObject {
                val speaker = inputSpeaker.trim().ifBlank {
                    if (messageKind == "fourth_wall") "作者" else "我"
                }
                put("speaker", speaker)
                put("message", userMessage)
                put("role", if (messageKind == "fourth_wall") "user" else inputRole)
                put("turn_id", turnId)
                put("timestamp", timestamp)
            })
            responses.forEachIndexed { index, response -> add(buildJsonObject {
                put("speaker", response.speaker)
                put("message", response.message)
                response.innerThought?.let { put("inner_thought", it) }
                put("role", if (response.speaker in setOf("旁白", "场景提示")) {
                    if (mode == "observe") "director" else "scene"
                } else {
                    "character"
                })
                put("turn_id", turnId)
                put("timestamp", timestamp)
                if (index == 0 && evidence.isNotEmpty()) put("evidence", evidenceToJson(evidence))
            }) }
        }
        val compacted = storageService.compactSessionTranscript(
            runId = runId,
            sessionId = sessionId,
            manifest = manifest,
            combined = combinedTranscript,
        )
        val updatedBase = buildJsonObject {
            manifest.forEach { (key, value) -> put(key, value) }
            put("turn_count", newTurnCount)
            put("current_turn_id", turnId)
            put("updated_at", timestamp)
            put("last_entry_preview", responses.lastOrNull()?.message.orEmpty())
            put("transcript", compacted.recent)
            put("transcript_start", compacted.startIndex)
            put("transcript_count", compacted.totalCount)
        }

        // 每轮提交后推导场景进度状态（对齐 Python _refresh_dialogue_scene_progress）
        if (!deriveState) {
            return persistSessionManifestPreservingControls(runId, sessionId, updatedBase)
        }

        val newTranscript = updatedBase["transcript"]?.jsonArray ?: JsonArray(emptyList())
        val transcriptMaps = newTranscript.mapNotNull { raw ->
            runCatching {
                raw.jsonObject.mapValues { (_, value) ->
                    when (value) {
                        is JsonObject -> value
                        else -> value.jsonPrimitive.contentOrNull
                    }
                }
            }.getOrNull()
        }
        val derivedState = SceneProgressState.deriveSceneProgressState(
            session = updatedBase,
            transcript = transcriptMaps,
            updatedAt = timestamp,
        )
        val updated = buildJsonObject {
            updatedBase.forEach { (key, value) -> put(key, value) }
            put("state", SceneProgressState.stateToJsonObject(derivedState))
        }

        val persisted = persistSessionManifestPreservingControls(runId, sessionId, updated)

        // 对齐 Python：每轮提交后把本轮的剧情事件/知识账本同步到 run 级世界记忆
        // （时间线按 turn_key 幂等去重，事实带 source_session_id 便于会话删除时清理）
        runCatching {
            worldMemory?.syncCompletedTurn(
                runId = runId,
                sessionId = sessionId,
                turnId = turnId,
                title = userMessage,
                participants = (updated["participants"]?.jsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() },
                events = (updated["state"]?.jsonObject?.get("signals")?.jsonObject
                    ?.get("recent")?.jsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                    .filter { it["turn_id"]?.jsonPrimitive?.contentOrNull == turnId },
                location = updated["state"]?.jsonObject?.get("scene")?.jsonObject
                    ?.get("location")?.jsonPrimitive?.contentOrNull.orEmpty(),
                timeHint = "",
                consistencyStatus = updated["consistency_monitor"]?.jsonObject?.get("latest")?.jsonObject
                    ?.get("status")?.jsonPrimitive?.contentOrNull.orEmpty(),
                knowledgeLedger = (updated["consistency_monitor"]?.jsonObject
                    ?.get("knowledge_ledger")?.jsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { runCatching { it.jsonObject }.getOrNull() },
                updatedAt = timestamp,
            )
        }
        return persisted
    }

    private fun refreshDerivedSessionState(
        runId: String,
        sessionId: String,
        manifest: JsonObject,
    ): JsonObject {
        val timestamp = nowIsoString()
        val transcript = manifest["transcript"]?.jsonArray ?: JsonArray(emptyList())
        val transcriptMaps = transcript.mapNotNull { raw ->
            runCatching {
                raw.jsonObject.mapValues { (_, value) ->
                    when (value) {
                        is JsonObject -> value
                        else -> value.jsonPrimitive.contentOrNull
                    }
                }
            }.getOrNull()
        }
        val derivedState = SceneProgressState.deriveSceneProgressState(
            session = manifest,
            transcript = transcriptMaps,
            updatedAt = timestamp,
        )
        val updated = buildJsonObject {
            manifest.forEach { (key, value) -> put(key, value) }
            put("state", SceneProgressState.stateToJsonObject(derivedState))
        }
        return persistSessionManifestPreservingControls(runId, sessionId, updated)
    }

    private fun syncWorldMemory(
        runId: String,
        sessionId: String,
        work: PostTurnWork,
        updated: JsonObject,
    ) {
        val timestamp = updated["state"]?.jsonObject?.get("scene")?.jsonObject
            ?.get("updated_at")?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { nowIsoString() }
        runCatching {
            worldMemory?.syncCompletedTurn(
                runId = runId,
                sessionId = sessionId,
                turnId = work.turnId,
                title = work.message,
                participants = (updated["participants"]?.jsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() },
                events = (updated["state"]?.jsonObject?.get("signals")?.jsonObject
                    ?.get("recent")?.jsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                    .filter { it["turn_id"]?.jsonPrimitive?.contentOrNull == work.turnId },
                location = updated["state"]?.jsonObject?.get("scene")?.jsonObject
                    ?.get("location")?.jsonPrimitive?.contentOrNull.orEmpty(),
                timeHint = "",
                consistencyStatus = updated["consistency_monitor"]?.jsonObject?.get("latest")?.jsonObject
                    ?.get("status")?.jsonPrimitive?.contentOrNull.orEmpty(),
                knowledgeLedger = (updated["consistency_monitor"]?.jsonObject
                    ?.get("knowledge_ledger")?.jsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { runCatching { it.jsonObject }.getOrNull() },
                updatedAt = timestamp,
            )
        }.onFailure { error ->
            PlatformLog.w(TAG, "Background world-memory update failed for $runId/$sessionId: ${error.message}", error)
        }
    }
}

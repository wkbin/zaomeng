package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.builtins.ListSerializer
import top.wkbin.zaomeng.data.api.WorldBookMatchDto
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
    private val operations: DialogueReplyOperationCoordinator? = null,
    private val projections: SessionProjectionService? = null,
    private val historyRetrieval: DialogueHistoryRetrievalService? = null,
) : DialogueDerivedScheduler {
    private val longTermMemory = LongTermMemoryService(storageService)
    private val originalKnowledge = OriginalKnowledgeService(storageService)
    companion object {
        private const val TAG = "DialogueService"
        private val TURN_COMMIT_FIELDS = setOf(
            "transcript",
            "transcript_start",
            "transcript_count",
            "turn_count",
            "current_turn_id",
            "updated_at",
            "last_entry_preview",
            WorldBookTiming.FIELD,
        )

        // 对齐 Python helpers.py:DIALOGUE_RESPONSE_MIN/MAX_MAX_TOKENS：
        // 推理模型会把 reasoning_content 计入输出预算，默认给足一轮预算避免重复推理
        const val DIALOGUE_RESPONSE_MIN_MAX_TOKENS = 8192
        const val DIALOGUE_RESPONSE_MAX_MAX_TOKENS = 16000
        const val DIALOGUE_RESPONSE_NON_REASONING_MIN_MAX_TOKENS = 1200
        const val DIALOGUE_RESPONSE_NON_REASONING_MAX_MAX_TOKENS = 4096

        /**
         * 解析对话回复的 max_tokens（对齐 Python _resolve_dialogue_max_tokens 的无配置分支）。
         */
        fun resolveDialogueMaxTokens(
            responseLimitHint: Int,
            reasoningEffort: String = "auto",
            pacing: String = "normal",
        ): Int {
            val limit = responseLimitHint
            val base = if (reasoningEffort.trim().equals("off", ignoreCase = true)) {
                if (limit > 0) {
                    minOf(
                        maxOf(DIALOGUE_RESPONSE_NON_REASONING_MIN_MAX_TOKENS, 520 + limit * 360),
                        DIALOGUE_RESPONSE_NON_REASONING_MAX_MAX_TOKENS,
                    )
                } else {
                    DIALOGUE_RESPONSE_NON_REASONING_MIN_MAX_TOKENS
                }
            } else if (limit > 0) {
                minOf(
                    maxOf(DIALOGUE_RESPONSE_MIN_MAX_TOKENS, 520 + limit * 360),
                    DIALOGUE_RESPONSE_MAX_MAX_TOKENS,
                )
            } else {
                DIALOGUE_RESPONSE_MIN_MAX_TOKENS
            }
            return when (normalizePacing(pacing)) {
                "brief" -> maxOf(1, (base * 0.5).toInt())
                "detailed" -> minOf(DIALOGUE_RESPONSE_MAX_MAX_TOKENS, (base * 1.5).toInt())
                else -> base
            }
        }

        internal fun normalizePacing(pacing: String): String = when (pacing.trim().lowercase()) {
            "brief", "detailed" -> pacing.trim().lowercase()
            else -> "normal"
        }

        internal fun applyPacingInstruction(
            messages: List<LlmClient.ChatMessage>,
            pacing: String,
        ): List<LlmClient.ChatMessage> {
            val instruction = when (normalizePacing(pacing)) {
                "brief" -> "本轮保持简短回复，每位发言者一到两句话。"
                "detailed" -> "本轮进行细腻描写，适当展开动作、感受与环境细节。"
                else -> return messages
            }
            val pacingMessage = LlmClient.ChatMessage(role = "system", content = instruction)
            return if (messages.lastOrNull()?.role == "system" && messages.any { it.role == "user" }) {
                messages.dropLast(1) + pacingMessage + messages.last()
            } else messages + pacingMessage
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
    private val pendingDerivedKicks = HashSet<String>()
    private val cancelledDerived = HashSet<String>()
    private val backgroundJobsLock = SimpleLock()
    private val derivedStore = DerivedOperationStore(storageService)
    private val replyOperationStore = ReplyOperationStore(storageService)
    private val sessionMutationLocks = HashMap<String, SimpleLock>()
    private val sessionMutationLocksLock = SimpleLock()

    private fun sessionKey(runId: String, sessionId: String): String = "${runId.trim()}:${sessionId.trim()}"

    private fun sessionMutationLock(runId: String, sessionId: String): SimpleLock =
        sessionMutationLocksLock.withLock {
            sessionMutationLocks.getOrPut(sessionKey(runId, sessionId)) { SimpleLock() }
        }

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
        pacing: String = "normal",
        speakerOverride: String = "",
        suppressTranscriptMessage: Boolean = false,
        includeInnerThoughts: Boolean = false,
        operationId: String = "",
    ): DialogueTurnResponse = outcomeToTurnResponse(
        replyDialogueTurnOutcome(
            runId, sessionId, message, messageKind, pacing, speakerOverride,
            suppressTranscriptMessage, includeInnerThoughts, operationId,
        ),
    )

    suspend fun replyDialogueTurnOutcome(
        runId: String,
        sessionId: String,
        message: String,
        messageKind: String = "user_input",
        pacing: String = "normal",
        speakerOverride: String = "",
        suppressTranscriptMessage: Boolean = false,
        includeInnerThoughts: Boolean = false,
        operationId: String = "",
    ): DialogueReplyOutcome {
        val coordinator = operations
            ?: return replyDialogueTurnInternalOutcome(
                runId, sessionId, message, messageKind, pacing, speakerOverride,
                suppressTranscriptMessage, includeInnerThoughts, operationId,
            )
        return when (val begin = coordinator.begin(
            ReplyOperationRequest(
                runId = runId,
                sessionId = sessionId,
                operationId = operationId,
                message = message,
                messageKind = messageKind,
                pacing = pacing,
                speakerOverride = speakerOverride,
                suppressTranscriptMessage = suppressTranscriptMessage,
                includeInnerThoughts = includeInnerThoughts,
            ),
        )) {
            is ReplyOperationBegin.Replay -> coordinator.outcome(begin.receipt, begin.sessionManifest, replayed = true)
            is ReplyOperationBegin.Active -> {
                val lease = begin.lease
                try {
                    val resume = lease.receipt.result?.takeIf {
                        lease.receipt.status == ReplyOperationStatus.COMMITTING
                    }
                    if (resume != null) {
                        val commit = commitTurnDetailed(
                            runId = runId,
                            sessionId = sessionId,
                            turnId = lease.receipt.turnId,
                            message = resume.message,
                            messageKind = resume.messageKind,
                            inputSpeaker = resume.inputSpeaker,
                            inputRole = resume.inputRole,
                            responses = resume.responses,
                            suppressTranscriptMessage = resume.suppressTranscriptMessage,
                            operation = lease,
                            resumeResult = resume,
                        )
                        coordinator.outcome(lease, commit)
                    } else {
                        replyDialogueTurnInternalOutcome(
                            runId, sessionId, message, messageKind, pacing, speakerOverride,
                            suppressTranscriptMessage, includeInnerThoughts, operationId, lease,
                        )
                    }
                } catch (error: Throwable) {
                    val recovered = coordinator.recoverFailure(lease, error)
                    if (recovered != null) recovered else throw error
                } finally {
                    coordinator.release(lease)
                }
            }
        }
    }

    private fun outcomeToTurnResponse(outcome: DialogueReplyOutcome): DialogueTurnResponse =
        DialogueTurnResponse(
            turnId = outcome.turnId,
            response = outcome.responses.firstOrNull() ?: DialogueResponse("", "", null),
        )

    private suspend fun replyDialogueTurnInternalOutcome(
        runId: String,
        sessionId: String,
        message: String,
        messageKind: String = "user_input",
        pacing: String = "normal",
        speakerOverride: String = "",
        suppressTranscriptMessage: Boolean = false,
        includeInnerThoughts: Boolean = false,
        operationId: String = "",
        operation: ActiveReplyOperation? = null,
    ): DialogueReplyOutcome {
        PlatformLog.d(TAG, "Replying to dialogue turn: run=$runId, session=$sessionId")

        // 1. Load session manifest（一次读取；DTO 与 JsonObject 复用同一份，避免重复读文件）
        val turnId = operation?.receipt?.turnId ?: operationId.ifBlank { randomUuid() }
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
        val payloadBuilder = DialoguePayloadBuilder(storageService, projections)
        val promptBuilder = DialoguePromptBuilder(promptLoader)
        val runManifest = storageService.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val basePayload = payloadBuilder.buildTurnPayload(
            runManifest = runManifest,
            session = sessionManifestJson,
            turnId = turnId,
            message = message,
            messageKind = messageKind,
            speakerOverride = speakerOverride,
            includeInnerThoughts = includeInnerThoughts || DialogueService.isInnerThoughtsEnhancerActive(sessionManifestJson),
            countsCurrentInput = !suppressTranscriptMessage,
        )
        val payload = enrichHistory(
            runId = runId,
            sessionId = sessionId,
            turnId = turnId,
            message = message,
            baseRevision = operation?.receipt?.baseRevision ?: sessionHistoryRevision(sessionManifestJson),
            sessionManifest = sessionManifestJson,
            payload = basePayload,
        )
        val conversationHistory = applyPacingInstruction(
            messages = promptBuilder.buildDialogueLlmMessages(payload = payload, retryOnEmpty = false),
            pacing = pacing,
        )

        // 4. 计算 max_tokens（对齐 Python _resolve_dialogue_max_tokens：
        //    推理模型会把 reasoning_content 计入输出预算，默认至少 8192，上限 16000）
        val responseLimit = ((payload["host_action"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?.get("response_limit_hint") as? Number)?.toInt() ?: 0
        val maxTokens = resolveDialogueMaxTokens(
            responseLimitHint = responseLimit,
            reasoningEffort = modelSettings["reasoning_effort"] as? String ?: "auto",
            pacing = pacing,
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
                    applyPacingInstruction(promptBuilder.buildDialogueLlmMessages(payload, retryOnEmpty = true), pacing)
                } else {
                    conversationHistory
                }
                val llmResponse = llmClient.chatCompletion(
                    messages = history,
                    model = modelName,
                    temperature = 0.7,
                    maxTokens = if (retry) minOf(maxTokens * 2, DIALOGUE_RESPONSE_MAX_MAX_TOKENS) else maxTokens,
                    requireJsonObject = false,
                    context = LlmRequestContext(
                        runId = runId,
                        sessionId = sessionId,
                        turnId = turnId,
                        operationId = operation?.receipt?.operationId ?: operationId,
                        attemptKind = if (retry) "format-retry" else "initial",
                    ),
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
        val commit = commitTurnDetailed(
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
            worldBookMatches = extractWorldBookMatches(payload),
            worldBookTimingDelta = payload[WorldBookTiming.DELTA] as? JsonObject,
            operation = operation,
        )

        return if (operations != null && operation != null) {
            operations.outcome(operation, commit)
        } else {
            DialogueReplyOutcome(
                operationId = commit.operationId,
                turnId = commit.turnId,
                replayed = commit.alreadyCommitted,
                baseRevision = commit.baseRevision,
                committedRevision = commit.committedRevision,
                sessionManifest = commit.manifest,
                appendedTranscript = commit.appendedTranscript,
                responses = commit.responses,
            )
        }
    }

    internal suspend fun enrichHistory(
        runId: String,
        sessionId: String,
        turnId: String,
        message: String,
        baseRevision: Long,
        sessionManifest: JsonObject,
        payload: Map<String, Any?>,
    ): Map<String, Any?> = historyRetrieval?.enrichHistory(
        runId,
        sessionId,
        turnId,
        message,
        baseRevision,
        sessionManifest,
        payload,
    ) ?: payload

    internal fun prepareSessionForGeneration(
        runId: String,
        sessionId: String,
        turnId: String,
        message: String,
    ): JsonObject {
        // turnId 可能来自请求体的 operation_id，并会拼进 turns/<turnId> 目录。
        val safeTurnId = PathSafety.validateStorageId(turnId, "turn_id")
        val stored = storageService.loadSessionManifest(runId, sessionId)
        return pluginRuleEngine?.beforeGeneration(runId, sessionId, safeTurnId, message, stored) ?: stored
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

    /** 以 latest 元数据为底，原子提交本轮历史与插件规则，并恰好推进一次 revision。 */
    private fun persistSessionManifestPreservingControls(
        runId: String,
        sessionId: String,
        turnId: String,
        message: String,
        responses: List<DialogueResponse>,
        candidate: JsonObject,
        includeDerivedState: Boolean,
        expectedRevision: Long? = null,
    ): JsonObject = storageService.mutateSessionHistory(runId, sessionId, expectedRevision) { latest ->
        val merged = buildJsonObject {
            latest.manifest.forEach { (key, value) ->
                if (key != "plugin_rule_directives") put(key, value)
            }
            TURN_COMMIT_FIELDS.forEach { key ->
                candidate[key]?.let { put(key, it) }
            }
            if (includeDerivedState) candidate["state"]?.let { put("state", it) }
        }
        val withPluginRules = pluginRuleEngine?.applyAfterTurn(
            runId = runId,
            sessionId = sessionId,
            turnId = turnId,
            message = message,
            responses = responses,
            session = merged,
        ) ?: merged
        SessionHistoryMutation(withPluginRules, Unit)
    }.manifest

    /** 兼容现有调用方；operation 编排使用 [commitTurnDetailed] 的结构化结果。 */
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
        worldBookMatches: List<WorldBookMatchDto> = emptyList(),
        existingSession: JsonObject? = null,
        worldBookTimingDelta: JsonObject? = null,
    ): JsonObject = commitTurnDetailed(
        runId = runId,
        sessionId = sessionId,
        turnId = turnId,
        message = message,
        messageKind = messageKind,
        inputSpeaker = inputSpeaker,
        inputRole = inputRole,
        responses = responses,
        suppressTranscriptMessage = suppressTranscriptMessage,
        evidence = evidence,
        worldBookMatches = worldBookMatches,
        existingSession = existingSession,
        worldBookTimingDelta = worldBookTimingDelta,
    ).manifest

    /** 流式与非流式共用的两阶段提交。 */
    suspend fun commitTurnDetailed(
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
        worldBookMatches: List<WorldBookMatchDto> = emptyList(),
        existingSession: JsonObject? = null,
        operation: ActiveReplyOperation? = null,
        resumeResult: ReplyOperationResult? = null,
        worldBookTimingDelta: JsonObject? = null,
    ): DialogueCommitResult {
        val safeTurnId = PathSafety.validateStorageId(turnId, "turn_id")
        val effectiveMessage = resumeResult?.message ?: message
        val effectiveMessageKind = resumeResult?.messageKind ?: messageKind
        val effectiveInputSpeaker = resumeResult?.inputSpeaker ?: inputSpeaker
        val effectiveInputRole = resumeResult?.inputRole ?: inputRole
        val effectiveResponses = resumeResult?.responses ?: responses
        val effectiveSuppress = resumeResult?.suppressTranscriptMessage ?: suppressTranscriptMessage
        val effectiveEvidence = resumeResult?.evidence?.let(::decodeEvidence) ?: evidence
        val effectiveWorldBookMatches = resumeResult?.worldBookMatches?.let(::decodeWorldBookMatches) ?: worldBookMatches
        val effectiveTiming = if (resumeResult == null) worldBookTimingDelta else {
            resumeResult.appendedTranscript.firstNotNullOfOrNull { it[WorldBookTiming.DELTA] as? JsonObject }
        }
        val archivedReplay = turnRecordExists(runId, sessionId, safeTurnId) &&
            archivedTranscriptContainsTurn(runId, sessionId, safeTurnId)

        val committed = sessionMutationLock(runId, sessionId).withLock {
            val sessionManifest = storageService.loadSessionManifest(runId, sessionId)
            if (archivedReplay || sessionContainsTurn(sessionManifest, safeTurnId)) {
                null
            } else {
                val currentRevision = sessionHistoryRevision(sessionManifest)
                val baseRevision = resumeResult?.committedRevision?.minus(1)
                    ?: operation?.receipt?.baseRevision
                    ?: currentRevision
                if (operation != null && currentRevision != baseRevision) {
                    throw SessionRevisionConflictException(baseRevision, currentRevision)
                }
                val committedTurn = updateSessionManifest(
                    runId = runId,
                    sessionId = sessionId,
                    newTurnCount = (sessionManifest["turn_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) + 1,
                    turnId = safeTurnId,
                    userMessage = effectiveMessage,
                    inputSpeaker = effectiveInputSpeaker,
                    inputRole = effectiveInputRole,
                    suppressUserMessage = effectiveSuppress,
                    messageKind = effectiveMessageKind,
                    responses = effectiveResponses,
                    evidence = effectiveEvidence,
                    worldBookMatches = effectiveWorldBookMatches,
                    worldBookTimingDelta = effectiveTiming,
                    mode = sessionManifest["mode"]?.jsonPrimitive?.contentOrNull ?: "observe",
                    manifest = sessionManifest,
                    deriveState = false,
                    operation = operation,
                    expectedRevision = currentRevision,
                )
                if (operation != null) operations?.markCompleted(operation)
                committedTurn
            }
        }
        val currentManifest = storageService.loadSessionManifest(runId, sessionId)
        if (committed == null) {
            PlatformLog.d(TAG, "Dialogue turn $safeTurnId already committed; skipped duplicate append")
            val existingResult = resumeResult ?: operation?.receipt?.result
            if (operation != null && existingResult != null) operations?.markCompleted(operation)
            if (existingResult != null) {
                scheduleDerivedResult(runId, sessionId, existingResult, DerivedOperationKind.POST_TURN)
            }
            return DialogueCommitResult(
                operationId = operation?.receipt?.operationId ?: safeTurnId,
                turnId = safeTurnId,
                alreadyCommitted = true,
                baseRevision = existingResult?.committedRevision?.minus(1)
                    ?: operation?.receipt?.baseRevision
                    ?: sessionHistoryRevision(currentManifest),
                committedRevision = existingResult?.committedRevision ?: sessionHistoryRevision(currentManifest),
                manifest = currentManifest,
                appendedTranscript = existingResult?.appendedTranscript
                    ?: storageService.transcriptReader.readByTurnId(runId, sessionId, safeTurnId).map { it.value },
                responses = existingResult?.responses.orEmpty(),
            )
        }
        projections?.rebuild(runId, sessionId)
        scheduleDerivedResult(runId, sessionId, committed.result, DerivedOperationKind.POST_TURN)
        return DialogueCommitResult(
            operationId = operation?.receipt?.operationId ?: safeTurnId,
            turnId = safeTurnId,
            alreadyCommitted = false,
            baseRevision = committed.result.committedRevision - 1,
            committedRevision = committed.result.committedRevision,
            manifest = committed.manifest,
            appendedTranscript = committed.result.appendedTranscript,
            responses = committed.result.responses,
        )
    }

    /** 只有实际 transcript 才能证明已提交；pending 的 current_turn_id 不算。 */
    private fun sessionContainsTurn(manifest: JsonObject, turnId: String): Boolean {
        val transcript = manifest["transcript"] as? JsonArray ?: return false
        return transcript.hasTurn(turnId)
    }

    /** 归档重试按轮查询，不再物化整段历史。 */
    private fun archivedTranscriptContainsTurn(runId: String, sessionId: String, turnId: String): Boolean =
        runCatching { storageService.transcriptReader.readByTurnId(runId, sessionId, turnId).isNotEmpty() }
            .getOrDefault(false)

    private fun turnRecordExists(runId: String, sessionId: String, turnId: String): Boolean =
        runCatching {
            storageService.exists(
                storageService.getDialogueSessionsDirectory(runId) / "$sessionId/turns/$turnId/turn.json",
            )
        }.getOrDefault(false)

    private fun JsonArray.hasTurn(turnId: String): Boolean = any { raw ->
        ((raw as? JsonObject)?.get("turn_id") as? JsonPrimitive)?.contentOrNull == turnId
    }

    /** 提交前持久化派生 intent；manifest 与 intent 的崩溃窗口由重放/启动扫描对账。 */
    private fun ensureDerivedIntent(
        runId: String,
        sessionId: String,
        result: ReplyOperationResult,
        kind: DerivedOperationKind = DerivedOperationKind.POST_TURN,
        activate: Boolean = false,
    ): DerivedOperation {
        if (backgroundJobsLock.withLock { sessionKey(runId, sessionId) in cancelledDerived }) {
            throw DerivedOperationInactiveException("Session was deleted")
        }
        return derivedStore.ensure(runId, sessionId, kind, result, activate)
    }

    override fun prepareDerivedResult(
        runId: String,
        sessionId: String,
        result: ReplyOperationResult,
        kind: DerivedOperationKind,
    ): DerivedOperation = ensureDerivedIntent(runId, sessionId, result, kind)

    override fun scheduleDerivedResult(
        runId: String,
        sessionId: String,
        result: ReplyOperationResult,
        kind: DerivedOperationKind,
    ): DerivedOperation {
        val operation = ensureDerivedIntent(runId, sessionId, result, kind, activate = true)
        kickDerivedWorker(runId, sessionId)
        return operation
    }

    /** 启动时补建第一阶段回执的派生 intent，并恢复孤儿 RUNNING。 */
    fun recoverDerivedOperations() {
        storageService.listRunIds().forEach { runId ->
            storageService.listDialogueSessionIds(runId).forEach { sessionId ->
                if (backgroundJobsLock.withLock { sessionKey(runId, sessionId) in cancelledDerived }) return@forEach
                derivedStore.reconcilePrepared(runId, sessionId)
                replyOperationStore.listCompleted(runId, sessionId).forEach { receipt ->
                    receipt.result?.let { result -> ensureDerivedIntent(runId, sessionId, result, activate = true) }
                }
                derivedStore.listActive(runId, sessionId).forEach { operation ->
                    if (operation.status == DerivedOperationStatus.RUNNING) derivedStore.recoverOrphan(operation)
                }
                kickDerivedWorker(runId, sessionId)
            }
        }
    }

    /** Stop session workers before removing documents, even if the DELETE caller disconnects. */
    suspend fun deleteSessionData(runId: String, sessionId: String): Boolean {
        val run = PathSafety.validateStorageId(runId, "run_id")
        val session = PathSafety.validateStorageId(sessionId, "session_id")
        return withContext(NonCancellable + platformIoDispatcher) {
            val directory = storageService.getDialogueSessionsDirectory(run) / session
            if (!storageService.exists(directory)) return@withContext false
            val key = sessionKey(run, session)
            val job = backgroundJobsLock.withLock {
                cancelledDerived += key
                pendingDerivedKicks.remove(key)
                backgroundJobs[key]
            }
            job?.cancelAndJoin()
            projections?.cancel(run, session)
            sessionMutationLock(run, session).withLock {
                if (!storageService.exists(directory)) return@withLock false
                if (storageService.exists(storageService.getDialogueSessionManifestFile(run, session))) {
                    derivedStore.cancelSession(run, session)
                }
                storageService.deleteDialogueSessionDocuments(run, session)
            }
        }
    }

    fun resetDerivedSession(runId: String, sessionId: String) {
        backgroundJobsLock.withLock { cancelledDerived -= sessionKey(runId, sessionId) }
        projections?.reset(runId, sessionId)
    }

    private fun kickDerivedWorker(runId: String, sessionId: String) {
        val key = sessionKey(runId, sessionId)
        backgroundJobsLock.withLock {
            if (key in cancelledDerived) return@withLock
            if (key in backgroundJobs) {
                pendingDerivedKicks += key
                return@withLock
            }
            backgroundJobs[key] = backgroundScope.launch {
                try {
                    drainDerivedOperations(runId, sessionId)
                } finally {
                    val shouldRestart = backgroundJobsLock.withLock {
                        backgroundJobs.remove(key)
                        pendingDerivedKicks.remove(key) && key !in cancelledDerived
                    }
                    if (shouldRestart) kickDerivedWorker(runId, sessionId)
                }
            }
        }
    }

    private suspend fun drainDerivedOperations(runId: String, sessionId: String) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val operation = derivedStore.claimNext(runId, sessionId, randomUuid())
            if (operation == null) {
                val nextAttemptAt = derivedStore.listActive(runId, sessionId)
                    .filter { it.status != DerivedOperationStatus.RUNNING }
                    .mapNotNull { operation -> operation.nextAttemptAt.takeIf { time -> time > 0 } }
                    .minOrNull()
                if (nextAttemptAt == null) return
                val waitMillis = nextAttemptAt - nowEpochMillis()
                if (waitMillis > 0) {
                    kotlinx.coroutines.delay(waitMillis)
                }
                continue
            }
            val startedAt = nowEpochMillis()
            try {
                executeDerivedOperation(operation)
            } catch (_: DerivedOperationInactiveException) {
                // 已被修正/删除取代的 owner 正常放弃，不再写失败回执。
            } catch (cancelled: CancellationException) {
                derivedStore.recoverOrphan(operation)
                throw cancelled
            } catch (error: Exception) {
                val effect = DerivedEffect.entries.firstOrNull {
                    operation.effects[it.name] in setOf(DerivedEffectStatus.PENDING, DerivedEffectStatus.RUNNING)
                } ?: DerivedEffect.SESSION_STATE
                derivedStore.failEffect(operation, effect, error)
            }
            PlatformLog.d(
                TAG,
                "Derived operation ${operation.operationId} finished with ${derivedStore.listAll(runId, sessionId)
                    .firstOrNull { it.operationId == operation.operationId }?.status}: " +
                    "elapsed_ms=${nowEpochMillis() - startedAt}",
            )
        }
    }

    private suspend fun executeDerivedOperation(initial: DerivedOperation) {
        var operation = initial
        val runId = operation.runId
        val sessionId = operation.sessionId
        var derivedSession: JsonObject? = null

        if (operation.needs(DerivedEffect.SESSION_STATE)) {
            try {
                derivedSession = sessionMutationLock(runId, sessionId).withLock {
                    storageService.updateSessionManifest(runId, sessionId) { latest ->
                        val base = if (operation.kind == DerivedOperationKind.CORRECTION) {
                            removeKnowledgeLedgerTurnSource(latest, operation.result.turnId)
                        } else {
                            latest
                        }
                        val merged = updateKnowledgeLedger(
                            session = base,
                            turnId = operation.result.turnId,
                            message = operation.result.message,
                            responses = operation.result.responses,
                        )
                        refreshDerivedSessionState(merged)
                    }
                }
                operation = derivedStore.completeEffect(operation, DerivedEffect.SESSION_STATE)
            } catch (inactive: DerivedOperationInactiveException) {
                return
            } catch (error: Exception) {
                operation = derivedStore.failEffect(operation, DerivedEffect.SESSION_STATE, error)
            }
        }

        if (operation.needs(DerivedEffect.WORLD_MEMORY) && !operation.needs(DerivedEffect.SESSION_STATE)) {
            try {
                val session = derivedSession ?: storageService.loadSessionManifest(runId, sessionId)
                syncWorldMemory(operation, session)
                operation = derivedStore.completeEffect(operation, DerivedEffect.WORLD_MEMORY)
            } catch (inactive: DerivedOperationInactiveException) {
                return
            } catch (error: Exception) {
                operation = derivedStore.failEffect(operation, DerivedEffect.WORLD_MEMORY, error)
            }
        }

        if (operation.needs(DerivedEffect.LONG_TERM_MEMORY)) {
            try {
                if (operation.kind == DerivedOperationKind.CORRECTION) {
                    longTermMemory.replaceTurn(
                        runId = runId,
                        sessionId = sessionId,
                        turnId = operation.result.turnId,
                        message = operation.result.message,
                        responses = operation.result.responses.map { response ->
                            mapOf("speaker" to response.speaker, "message" to response.message)
                        },
                    )
                } else {
                    longTermMemory.appendTurn(
                        runId = runId,
                        sessionId = sessionId,
                        turnId = operation.result.turnId,
                        message = operation.result.message,
                        responses = operation.result.responses.map { response ->
                            mapOf("speaker" to response.speaker, "message" to response.message)
                        },
                    )
                }
                operation = derivedStore.completeEffect(operation, DerivedEffect.LONG_TERM_MEMORY)
            } catch (inactive: DerivedOperationInactiveException) {
                return
            } catch (error: Exception) {
                operation = derivedStore.failEffect(operation, DerivedEffect.LONG_TERM_MEMORY, error)
            }
        }

        if (operation.needs(DerivedEffect.ORIGINAL_ENSURE)) {
            try {
                val runManifest = storageService.readRunManifest(runId)
                if (runManifest == null || !originalKnowledge.hasSource(runManifest)) {
                    operation = derivedStore.skipEffect(operation, DerivedEffect.ORIGINAL_ENSURE)
                } else {
                    val participants = (derivedSession?.get("participants")?.jsonArray ?: JsonArray(emptyList()))
                        .mapNotNull { it.jsonPrimitive.contentOrNull }
                    originalKnowledge.ensure(
                        runManifest = runManifest,
                        characterNames = participants + operation.result.responses.map { it.speaker },
                    )
                    operation = derivedStore.completeEffect(operation, DerivedEffect.ORIGINAL_ENSURE)
                }
            } catch (inactive: DerivedOperationInactiveException) {
                return
            } catch (error: Exception) {
                operation = derivedStore.failEffect(operation, DerivedEffect.ORIGINAL_ENSURE, error)
            }
        }
    }

    private fun DerivedOperation.needs(effect: DerivedEffect): Boolean =
        effects[effect.name] !in setOf(DerivedEffectStatus.DONE, DerivedEffectStatus.SKIPPED)

    private fun removeKnowledgeLedgerTurnSource(session: JsonObject, turnId: String): JsonObject {
        val monitor = session["consistency_monitor"]?.jsonObject ?: return session
        val ledger = (monitor["knowledge_ledger"]?.jsonArray ?: return session).mapNotNull { raw ->
            val entry = raw as? JsonObject ?: return@mapNotNull null
            val locked = entry["locked"]?.jsonPrimitive?.contentOrNull == "true" ||
                entry["locked"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
            val declaredField = entry["source_turn_ids"]
            val sources = if (declaredField != null) {
                declaredField.jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            } else {
                listOfNotNull(entry["turn_id"]?.jsonPrimitive?.contentOrNull)
            }
            val remaining = sources.filter { it != turnId }
            if (remaining.isEmpty() && !locked) {
                null
            } else {
                buildJsonObject {
                    entry.forEach { (key, value) -> put(key, value) }
                    put("source_turn_ids", buildJsonArray { remaining.forEach { add(JsonPrimitive(it)) } })
                }
            }
        }
        return buildJsonObject {
            session.forEach { (key, value) -> put(key, value) }
            put("consistency_monitor", buildJsonObject {
                monitor.forEach { (key, value) -> put(key, value) }
                put("knowledge_ledger", JsonArray(ledger))
            })
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
                            put("source_turn_ids", buildJsonArray {
                                val declaredField = ledger[index]["source_turn_ids"]
                                val existingSources = if (declaredField != null) {
                                    declaredField.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
                                } else {
                                    listOfNotNull(ledger[index]["turn_id"]?.jsonPrimitive?.contentOrNull)
                                }
                                (existingSources + turnId).distinct().forEach { add(JsonPrimitive(it)) }
                            })
                            put("updated_at", now)
                        }
                    } else {
                        ledger += buildJsonObject {
                            put("fact", fact)
                            put("source", speaker)
                            put("turn_id", turnId)
                            put("source_turn_ids", buildJsonArray { add(JsonPrimitive(turnId)) })
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
        val safeTurnId = PathSafety.validateStorageId(turnId, "turn_id")
        val sessionDir = requireNotNull(storageService.getDialogueSessionManifestFile(runId, sessionId).parent)

        val turnRecord = TurnRecord(
            turnId = safeTurnId,
            message = message,
            messageKind = messageKind,
            inputSpeaker = inputSpeaker,
            responses = responses,
            timestamp = nowEpochMillis(),
            evidence = evidence,
        )

        val turnFile = sessionDir / "turns/$safeTurnId/turn.json"
        storageService.writeExistingSessionText(runId, sessionId, turnFile, Json.encodeToString(TurnRecord.serializer(), turnRecord))
    }

    internal fun extractWorldBookMatches(payload: Map<String, Any?>): List<WorldBookMatchDto> =
        (payload["world_book_matches"] as? List<*>).orEmpty().filterIsInstance<WorldBookMatchDto>()

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

    private fun decodeEvidence(value: JsonArray): List<OriginalKnowledgeEntryDto> =
        json.decodeFromJsonElement(ListSerializer(OriginalKnowledgeEntryDto.serializer()), value)

    private fun decodeWorldBookMatches(value: JsonArray): List<WorldBookMatchDto> =
        json.decodeFromJsonElement(ListSerializer(WorldBookMatchDto.serializer()), value)

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

    private data class CommittedTurn(
        val manifest: JsonObject,
        val result: ReplyOperationResult,
    )

    /** 先持久化 COMMITTING result，再写 turn 和带 revision 的 manifest。 */
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
        worldBookMatches: List<WorldBookMatchDto>,
        worldBookTimingDelta: JsonObject?,
        mode: String = "observe",
        manifest: JsonObject,
        deriveState: Boolean = true,
        operation: ActiveReplyOperation? = null,
        expectedRevision: Long? = null,
    ): CommittedTurn {
        val timestamp = nowIsoString()
        val transcript = manifest["transcript"] as? JsonArray ?: JsonArray(emptyList())
        val newEntries = buildList {
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
                if (index == 0 && worldBookMatches.isNotEmpty()) {
                    put("world_book_matches", Json.encodeToJsonElement(ListSerializer(WorldBookMatchDto.serializer()), worldBookMatches))
                }
                if (index == 0 && worldBookTimingDelta != null) put(WorldBookTiming.DELTA, worldBookTimingDelta)
            }) }
        }
        val baseCount = sessionTranscriptCount(manifest)
        val result = ReplyOperationResult(
            turnId = turnId,
            committedRevision = sessionHistoryRevision(manifest) + 1,
            transcriptCountAtCommit = baseCount + newEntries.size,
            appendedTranscript = newEntries.mapIndexed { index, item ->
                buildJsonObject {
                    item.forEach { (key, value) -> put(key, value) }
                    put("seq", baseCount + index)
                }
            },
            message = userMessage,
            messageKind = messageKind,
            inputSpeaker = inputSpeaker,
            inputRole = inputRole,
            responses = responses,
            suppressTranscriptMessage = suppressUserMessage,
            evidence = evidenceToJson(evidence),
            worldBookMatches = Json.encodeToJsonElement(
                ListSerializer(WorldBookMatchDto.serializer()),
                worldBookMatches,
            ).jsonArray,
        )
        if (operation != null) operations?.markCommitting(operation, result)
        ensureDerivedIntent(runId, sessionId, result)
        saveTurn(runId, sessionId, turnId, userMessage, messageKind, inputSpeaker, responses, evidence)

        val combinedTranscript = buildJsonArray {
            transcript.forEach(::add)
            newEntries.forEach(::add)
        }
        val updatedBase = buildJsonObject {
            manifest.forEach { (key, value) -> put(key, value) }
            put("turn_count", newTurnCount)
            put("current_turn_id", turnId)
            put("updated_at", timestamp)
            put("last_entry_preview", responses.lastOrNull()?.message.orEmpty())
            if (worldBookTimingDelta != null && responses.isNotEmpty()) {
                put(WorldBookTiming.FIELD, WorldBookTiming.apply(manifest, worldBookTimingDelta))
            }
            // archive 只能由 mutateSessionHistory 在 revision 校验后的同一锁内写入。
            put("transcript", combinedTranscript)
            put("transcript_count", combinedTranscript.size)
        }

        if (!deriveState) {
            return CommittedTurn(
                persistSessionManifestPreservingControls(
                    runId = runId,
                    sessionId = sessionId,
                    turnId = turnId,
                    message = userMessage,
                    responses = responses,
                    candidate = updatedBase,
                    includeDerivedState = false,
                    expectedRevision = expectedRevision,
                ),
                result,
            )
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
        val persisted = persistSessionManifestPreservingControls(
            runId = runId,
            sessionId = sessionId,
            turnId = turnId,
            message = userMessage,
            responses = responses,
            candidate = updated,
            includeDerivedState = true,
            expectedRevision = expectedRevision,
        )

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
        return CommittedTurn(persisted, result)
    }

    private fun refreshDerivedSessionState(manifest: JsonObject): JsonObject {
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
        return updated
    }

    private fun syncWorldMemory(operation: DerivedOperation, updated: JsonObject) {
        val runId = operation.runId
        val sessionId = operation.sessionId
        val result = operation.result
        val timestamp = updated["state"]?.jsonObject?.get("scene")?.jsonObject
            ?.get("updated_at")?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { nowIsoString() }
        worldMemory?.syncCompletedTurn(
            runId = runId,
            sessionId = sessionId,
            turnId = result.turnId,
            title = result.message,
            participants = (updated["participants"]?.jsonArray ?: JsonArray(emptyList()))
                .mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() },
            events = (updated["state"]?.jsonObject?.get("signals")?.jsonObject
                ?.get("recent")?.jsonArray ?: JsonArray(emptyList()))
                .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                .filter { it["turn_id"]?.jsonPrimitive?.contentOrNull == result.turnId },
            location = updated["state"]?.jsonObject?.get("scene")?.jsonObject
                ?.get("location")?.jsonPrimitive?.contentOrNull.orEmpty(),
            timeHint = "",
            consistencyStatus = updated["consistency_monitor"]?.jsonObject?.get("latest")?.jsonObject
                ?.get("status")?.jsonPrimitive?.contentOrNull.orEmpty(),
            knowledgeLedger = (updated["consistency_monitor"]?.jsonObject
                ?.get("knowledge_ledger")?.jsonArray ?: JsonArray(emptyList()))
                .mapNotNull { runCatching { it.jsonObject }.getOrNull() },
            updatedAt = timestamp,
            replaceSource = operation.kind == DerivedOperationKind.CORRECTION,
        )
    }
}

package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import okio.Path
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.nowEpochMillis
import top.wkbin.zaomeng.platform.nowIsoString
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
) {
    companion object {
        private const val TAG = "DialogueService"

        // 对齐 Python helpers.py:DIALOGUE_RESPONSE_MIN/MAX_MAX_TOKENS：
        // 推理模型会把 reasoning_content 计入输出预算，默认给足一轮预算避免重复推理
        const val DIALOGUE_RESPONSE_MIN_MAX_TOKENS = 8192
        const val DIALOGUE_RESPONSE_MAX_MAX_TOKENS = 16000

        /**
         * 解析对话回复的 max_tokens（对齐 Python _resolve_dialogue_max_tokens 的无配置分支）。
         */
        fun resolveDialogueMaxTokens(responseLimitHint: Int): Int {
            val limit = responseLimitHint
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
        val responses: List<DialogueResponse>,
        val timestamp: Long
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
        suppressTranscriptMessage: Boolean = false,
        includeInnerThoughts: Boolean = false,
        operationId: String = "",
    ): DialogueTurnResponse {
        PlatformLog.d(TAG, "Replying to dialogue turn: run=$runId, session=$sessionId")

        // 1. Load session manifest（一次读取；DTO 与 JsonObject 复用同一份，避免重复读文件）
        val sessionManifestJson = storageService.loadSessionManifest(runId, sessionId)
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
        val turnId = operationId.ifBlank { randomUuid() }
        val payload = payloadBuilder.buildTurnPayload(
            runManifest = runManifest,
            session = sessionManifestJson,
            turnId = turnId,
            message = message,
            messageKind = messageKind,
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
        val maxTokens = resolveDialogueMaxTokens(responseLimit)

        // 5. 调用 LLM API（对齐 Python generate_dialogue_responses：解析失败重试一次，maxTokens 翻倍 + retryOnEmpty 提示词）
        val llmClient = llmClient
            ?: throw IllegalStateException("LLM not configured (client unavailable)")
        val forbiddenSpeakers = listOf(
            sessionManifest.controlledCharacter.orEmpty(),
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
                )
                val responseContent = llmResponse.choices.firstOrNull()?.message?.content
                    ?: throw IllegalArgumentException("Empty response from LLM")
                responses = parseDialogueResponses(
                    responseContent,
                    participants,
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
            responses = finalResponses,
            suppressTranscriptMessage = suppressTranscriptMessage,
            existingSession = sessionManifestJson,
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
     * 对齐 Python parse_dialogue_responses：解析 JSON 数组 [{speaker, message, inner_thought}]，
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

    /**
     * 提交一轮对话结果：保存 turn 文件并更新会话 manifest（非流式与流式共用）。
     */
    suspend fun commitTurn(
        runId: String,
        sessionId: String,
        turnId: String,
        message: String,
        messageKind: String,
        responses: List<DialogueResponse>,
        suppressTranscriptMessage: Boolean = false,
        // 性能：调用方已加载的最新 manifest 直接复用（避免每回复重复读 session_manifest.json）
        existingSession: JsonObject? = null,
    ): JsonObject {
        val sessionManifest = existingSession ?: storageService.loadSessionManifest(runId, sessionId)
        saveTurn(runId, sessionId, turnId, message, messageKind, responses)
        return updateSessionManifest(
            runId = runId,
            sessionId = sessionId,
            newTurnCount = (sessionManifest["turn_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) + 1,
            turnId = turnId,
            userMessage = message,
            suppressUserMessage = suppressTranscriptMessage,
            responses = responses,
            mode = sessionManifest["mode"]?.jsonPrimitive?.contentOrNull ?: "observe",
            manifest = sessionManifest,
        )
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
        responses: List<DialogueResponse>
    ) {
        val turnDir = storageService.getDialogueSessionsDirectory(runId) / "$sessionId/turns/$turnId"
        storageService.mkdirs(turnDir)

        val turnRecord = TurnRecord(
            turnId = turnId,
            message = message,
            messageKind = messageKind,
            responses = responses,
            timestamp = nowEpochMillis()
        )

        val turnFile = turnDir / "turn.json"
        storageService.writeTextAtomically(turnFile, Json.encodeToString(TurnRecord.serializer(), turnRecord))
    }

    /**
     * Update session manifest with new turn count.
     */
    private fun updateSessionManifest(
        runId: String,
        sessionId: String,
        newTurnCount: Int,
        turnId: String,
        userMessage: String,
        suppressUserMessage: Boolean,
        responses: List<DialogueResponse>,
        mode: String = "observe",
        manifest: JsonObject,
    ): JsonObject {
        val manifestFile = storageService.getDialogueSessionManifestFile(runId, sessionId)

        val timestamp = nowIsoString()
        val transcript = manifest["transcript"] as? JsonArray ?: JsonArray(emptyList())
        val updatedBase = buildJsonObject {
            manifest.forEach { (key, value) -> put(key, value) }
            put("turn_count", newTurnCount)
            put("current_turn_id", turnId)
            put("updated_at", timestamp)
            put("last_entry_preview", responses.lastOrNull()?.message.orEmpty())
            put("transcript", buildJsonArray {
                transcript.forEach(::add)
                if (!suppressUserMessage) add(buildJsonObject {
                    put("speaker", "我")
                    put("message", userMessage)
                    put("role", "user")
                    put("turn_id", turnId)
                    put("timestamp", timestamp)
                })
                responses.forEach { response -> add(buildJsonObject {
                    put("speaker", response.speaker)
                    put("message", response.message)
                    response.innerThought?.let { put("inner_thought", it) }
                    // 对齐 Python serialize_transcript：旁白/场景提示的 role 按 mode 标记 scene/director
                    put("role", if (response.speaker in setOf("旁白", "场景提示")) {
                        if (mode == "observe") "director" else "scene"
                    } else {
                        "character"
                    })
                    put("turn_id", turnId)
                    put("timestamp", timestamp)
                }) }
            })
        }

        // 每轮提交后推导场景进度状态（对齐 Python _refresh_dialogue_scene_progress）
        val newTranscript = updatedBase["transcript"]?.jsonArray ?: JsonArray(emptyList())
        val transcriptMaps = newTranscript.mapNotNull { raw ->
            runCatching {
                raw.jsonObject.mapKeys { it.key.toString() }.mapValues { (_, value) ->
                    when (value) {
                        is JsonObject -> value.mapKeys { it.key.toString() }
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

        storageService.writeTextAtomically(manifestFile, Json.encodeToString(JsonObject.serializer(), updated))

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
        return updated
    }
}

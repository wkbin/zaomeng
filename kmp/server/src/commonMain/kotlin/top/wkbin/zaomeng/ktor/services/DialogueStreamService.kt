package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import top.wkbin.zaomeng.ktor.models.DialogueResponse
import top.wkbin.zaomeng.ktor.utils.DialogueStreamParser
import top.wkbin.zaomeng.ktor.utils.StreamEvent
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.nowEpochMillis
import top.wkbin.zaomeng.platform.randomUuid

/**
 * 对话流式服务
 *
 * 处理流式对话响应，使用 SSE (Server-Sent Events)。
 * 流式结束后从完整输出解析 responses 并提交 turn（保存 + 更新会话 manifest）。
 */
class DialogueStreamService(
    private val storageService: StorageService,
    private val llmClient: LlmClient,
    private val promptLoader: PromptLoader,
    private val dialogue: DialogueService,
) {
    companion object {
        private const val TAG = "DialogueStreamService"
    }

    /**
     * 流式回复对话轮次
     *
     * @param runId 运行 ID
     * @param sessionId 会话 ID
     * @param message 用户输入消息
     * @param messageKind 消息类型
     * @param includeInnerThoughts 是否包含内心想法
     * @param operationId 操作 ID（作为 turn_id，供幂等/恢复）
     * @return Flow<StreamEvent> 流式事件
     */
    suspend fun replyDialogueTurnStream(
        runId: String,
        sessionId: String,
        message: String,
        messageKind: String = "user_input",
        includeInnerThoughts: Boolean = false,
        operationId: String = "",
        suppressTranscriptMessage: Boolean = false,
        includeModelReasoning: Boolean = false,
    ): Flow<StreamEvent> = flow {
        val startedAt = nowEpochMillis()
        PlatformLog.d(TAG, "Starting streaming dialogue reply: run=$runId, session=$sessionId")

        // 1. 加载模型设置
        val modelSettings = storageService.loadModelSettings()
        val modelName = modelSettings["model"] as? String ?: "gpt-4"
        val settingsLoadedAt = nowEpochMillis()

        // 2. 构建提示词（对齐 Python build_dialogue_llm_messages 完整管道）
        val payloadBuilder = DialoguePayloadBuilder(storageService)
        val promptBuilder = DialoguePromptBuilder(promptLoader)
        val runManifest = storageService.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val sessionManifestJson = storageService.loadSessionManifest(runId, sessionId)
        val manifestsLoadedAt = nowEpochMillis()
        val turnId = operationId.ifBlank { randomUuid() }
        val payload = payloadBuilder.buildTurnPayload(
            runManifest = runManifest,
            session = sessionManifestJson,
            turnId = turnId,
            message = message,
            messageKind = messageKind,
            includeInnerThoughts = includeInnerThoughts || DialogueService.isInnerThoughtsEnhancerActive(sessionManifestJson),
        )
        val payloadBuiltAt = nowEpochMillis()

        // 3. 构建对话历史
        val conversationHistory = promptBuilder.buildDialogueLlmMessages(
            payload = payload,
            retryOnEmpty = false,
        )
        val promptBuiltAt = nowEpochMillis()
        val promptChars = conversationHistory.sumOf { it.content?.length ?: 0 }

        // 4. 计算 max_tokens（对齐 Python：推理模型 reasoning_content 计入预算，默认至少 8192）
        val responseLimit = ((payload["host_action"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?.get("response_limit_hint") as? Number)?.toInt() ?: 0
        val maxTokens = DialogueService.resolveDialogueMaxTokens(
            responseLimitHint = responseLimit,
            reasoningEffort = modelSettings["reasoning_effort"] as? String ?: "auto",
        )

        // 5. 创建流式解析器（密钥由 LlmClient 从活动模型配置与 Keystore 解析，无需在此获取）
        val parser = DialogueStreamParser(chunkSize = 24)
        val modelStartedAt = nowEpochMillis()
        var firstContentAt: Long? = null

        // 6. 调用 LLM 流式 API
        try {
            llmClient.chatCompletionStream(
                messages = conversationHistory,
                model = modelName,
                temperature = 0.7,
                maxTokens = maxTokens,
                // 请求参数（thinking / reasoning_effort）由模型设置的 reasoning_effort 决定（对齐 Python _apply_reasoning_controls）；
                // enableReasoning 仅控制下方 onReasoning 是否把推理过程透传为 model_reasoning delta，不影响模型是否思考
                enableReasoning = includeModelReasoning,
                requireJsonObject = false,
                onReasoning = { reasoning ->
                    // 推理过程透传为 model_reasoning delta（对齐 Python on_reasoning → SSE model_reasoning）
                    if (includeModelReasoning) {
                        emit(StreamEvent(index = 0, speaker = "", role = "reasoning", field = "model_reasoning", text = reasoning))
                    }
                },
            ).collect { contentDelta ->
                if (firstContentAt == null) {
                    val firstAt = nowEpochMillis()
                    firstContentAt = firstAt
                    PlatformLog.d(
                        TAG,
                        "Dialogue first content: preprocess_ms=${modelStartedAt - startedAt}, " +
                            "ttft_ms=${firstAt - modelStartedAt}, prompt_chars=$promptChars",
                    )
                }
                parser.feed(contentDelta).forEach { emit(it) }
            }
            val firstModelCompletedAt = nowEpochMillis()

            // 7. 流结束：从完整输出解析 responses 并提交 turn（对齐 Python generate_and_commit）
            val full = parser.fullContent()
            val inputMap = (payload["input"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
            val participants = ((inputMap["participants"] as? List<*>) ?: emptyList<Any?>())
                .mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() } + listOf("旁白", "场景提示")
            val forbidden = listOf(
                (inputMap["controlled_character"]?.toString()?.trim().orEmpty()),
                (inputMap["speaker"]?.toString()?.trim().orEmpty()),
            )
            // 解析失败重试一次（对齐 Python generate_dialogue_responses 的 0..1 重试循环：
            // retryOnEmpty 提示词 + maxTokens 翻倍到上限；第二次仍失败则抛错 → SSE error）
            var responses: List<DialogueResponse>
            try {
                responses = DialogueResponseParser.parse(full, participants, forbidden)
            } catch (e: IllegalArgumentException) {
                val plainTextFallback = DialogueResponseParser.parseSingleSpeakerPlainText(
                    full,
                    participants,
                    forbidden,
                )
                if (plainTextFallback != null) {
                    responses = plainTextFallback
                    PlatformLog.w(
                        TAG,
                        "Streaming reply used single-speaker plain-text fallback after format error: ${e.message}",
                    )
                } else {
                    PlatformLog.e(
                        TAG,
                        "Streaming reply parse failed (attempt 1/2): ${e.message}" +
                            "\n--- full output (first 800 chars) ---\n${full.take(800)}" +
                            "\n--- participants: $participants / forbidden: $forbidden ---",
                    )
                    val retryHistory = promptBuilder.buildDialogueLlmMessages(payload, retryOnEmpty = true)
                    val retryMaxTokens = minOf(maxTokens * 2, DialogueService.DIALOGUE_RESPONSE_MAX_MAX_TOKENS)
                    emit(
                        StreamEvent(
                            index = 0,
                            speaker = "",
                            role = "status",
                            field = "message",
                            text = "正在重新整理回复…",
                            kind = "reset",
                        ),
                    )
                    val retryParser = DialogueStreamParser(chunkSize = 24)
                    val retryStartedAt = nowEpochMillis()
                    var retryFirstContentAt: Long? = null
                    llmClient.chatCompletionStream(
                        messages = retryHistory,
                        model = modelName,
                        temperature = 0.7,
                        maxTokens = retryMaxTokens,
                        enableReasoning = includeModelReasoning,
                        requireJsonObject = false,
                        onReasoning = { reasoning ->
                            if (includeModelReasoning) {
                                emit(
                                    StreamEvent(
                                        index = 0,
                                        speaker = "",
                                        role = "reasoning",
                                        field = "model_reasoning",
                                        text = reasoning,
                                    ),
                                )
                            }
                        },
                    ).collect { contentDelta ->
                        if (retryFirstContentAt == null) retryFirstContentAt = nowEpochMillis()
                        retryParser.feed(contentDelta).forEach { emit(it) }
                    }
                    val retryFull = retryParser.fullContent().trim()
                    PlatformLog.d(
                        TAG,
                        "Streaming reply retry (attempt 2/2) returned in ${nowEpochMillis() - retryStartedAt}ms " +
                            "(ttft_ms=${retryFirstContentAt?.minus(retryStartedAt) ?: -1}): ${retryFull.take(200)}",
                    )
                    responses = DialogueResponseParser.parse(retryFull, participants, forbidden)
                }
            }
            val parsedAt = nowEpochMillis()
            dialogue.commitTurn(
                runId = runId,
                sessionId = sessionId,
                turnId = turnId,
                message = message,
                messageKind = messageKind,
                responses = responses,
                suppressTranscriptMessage = suppressTranscriptMessage,
                existingSession = sessionManifestJson,
            )
            val committedAt = nowEpochMillis()
            PlatformLog.d(
                TAG,
                "Streaming dialogue reply completed and committed: total_ms=${committedAt - startedAt}, " +
                    "settings_ms=${settingsLoadedAt - startedAt}, manifests_ms=${manifestsLoadedAt - settingsLoadedAt}, " +
                    "payload_ms=${payloadBuiltAt - manifestsLoadedAt}, prompt_ms=${promptBuiltAt - payloadBuiltAt}, " +
                    "first_model_ms=${firstModelCompletedAt - modelStartedAt}, parse_retry_ms=${parsedAt - firstModelCompletedAt}, " +
                    "commit_ms=${committedAt - parsedAt}, prompt_chars=$promptChars",
            )
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Error during streaming dialogue reply after ${nowEpochMillis() - startedAt}ms", e)
            throw e
        }
    }
}

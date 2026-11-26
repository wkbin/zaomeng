package top.wkbin.zaomeng.ktor.services

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.DialoguePromptMessageDto
import top.wkbin.zaomeng.data.api.DialoguePromptPreviewDto
import top.wkbin.zaomeng.data.api.WorldBookMatchDto
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
sealed interface DialogueStreamFrame {
    data class PromptPreview(val preview: DialoguePromptPreviewDto) : DialogueStreamFrame
    data class Delta(val event: StreamEvent) : DialogueStreamFrame
    data class Completed(val outcome: DialogueReplyOutcome) : DialogueStreamFrame
}

internal fun dialoguePromptPreview(
    turnId: String,
    attempt: Int,
    messages: List<LlmClient.ChatMessage>,
    worldBookMatches: List<WorldBookMatchDto>,
): DialoguePromptPreviewDto {
    var remaining = 64_000
    val previews = messages.map { message ->
        val content = message.content.orEmpty()
        val limit = minOf(remaining, 24_000)
        val text = if (content.length <= limit) content else content.take(limit).let {
            if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
        }
        remaining -= text.length
        DialoguePromptMessageDto(message.role, text, truncated = text.length < content.length)
    }
    return DialoguePromptPreviewDto(turnId, attempt, previews, worldBookMatches.filter { it.status == "injected" })
}

class DialogueStreamService(
    private val storageService: StorageService,
    private val llmClient: LlmClient,
    private val promptLoader: PromptLoader,
    private val dialogue: DialogueService,
    private val operations: DialogueReplyOperationCoordinator? = null,
    private val projections: SessionProjectionService? = null,
) {
    companion object {
        private const val TAG = "DialogueStreamService"
    }

    /** 在 SSE 开始前完成认领，使指纹/revision 冲突可以映射为 HTTP 409。 */
    suspend fun preflight(
        runId: String,
        sessionId: String,
        message: String,
        messageKind: String,
        pacing: String,
        speakerOverride: String,
        includeInnerThoughts: Boolean,
        operationId: String,
        suppressTranscriptMessage: Boolean,
    ): ReplyOperationBegin? = operations?.begin(
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
    )

    fun replyDialogueTurnFrames(
        runId: String,
        sessionId: String,
        message: String,
        messageKind: String = "user_input",
        pacing: String = "normal",
        speakerOverride: String = "",
        includeInnerThoughts: Boolean = false,
        operationId: String = "",
        suppressTranscriptMessage: Boolean = false,
        includeModelReasoning: Boolean = false,
        prepared: ReplyOperationBegin? = null,
        includePromptPreview: Boolean = false,
    ): Flow<DialogueStreamFrame> = flow {
        val coordinator = operations
        val begin = prepared ?: coordinator?.begin(
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
        )
        if (begin is ReplyOperationBegin.Replay) {
            emit(DialogueStreamFrame.Completed(coordinator!!.outcome(begin.receipt, begin.sessionManifest, replayed = true)))
            return@flow
        }
        val lease = (begin as? ReplyOperationBegin.Active)?.lease
        try {
            val resume = lease?.receipt?.result?.takeIf {
                lease.receipt.status == ReplyOperationStatus.COMMITTING
            }
            if (lease != null && resume != null && coordinator != null) {
                val commit = dialogue.commitTurnDetailed(
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
                emit(DialogueStreamFrame.Completed(coordinator.outcome(lease, commit)))
                return@flow
            }
            var completed: DialogueReplyOutcome? = null
            replyDialogueTurnStream(
                runId = runId,
                sessionId = sessionId,
                message = message,
                messageKind = messageKind,
                pacing = pacing,
                speakerOverride = speakerOverride,
                includeInnerThoughts = includeInnerThoughts,
                operationId = operationId,
                suppressTranscriptMessage = suppressTranscriptMessage,
                includeModelReasoning = includeModelReasoning,
                operation = lease,
                onPromptPreview = if (includePromptPreview) {
                    { preview -> emit(DialogueStreamFrame.PromptPreview(preview)) }
                } else null,
                onCompleted = { completed = it },
            ).collect { event -> emit(DialogueStreamFrame.Delta(event)) }
            emit(DialogueStreamFrame.Completed(requireNotNull(completed) { "Dialogue stream completed without a commit" }))
        } catch (error: Throwable) {
            val recovered = lease?.let { coordinator?.recoverFailure(it, error) }
            if (recovered != null) {
                emit(DialogueStreamFrame.Completed(recovered))
            } else {
                throw error
            }
        } finally {
            lease?.let { coordinator?.release(it) }
        }
    }

    fun abort(prepared: ReplyOperationBegin, cause: Throwable) {
        val lease = (prepared as? ReplyOperationBegin.Active)?.lease ?: return
        try {
            operations?.abandonBeforeHandoff(lease, cause)
        } finally {
            operations?.release(lease)
        }
    }

    fun operationStatus(runId: String, sessionId: String, operationId: String): JsonObject {
        val receipt = operations?.get(runId, sessionId, operationId)
            ?: throw NoSuchElementException("Operation not found: $operationId")
        val result = receipt.result?.takeIf { receipt.status == ReplyOperationStatus.COMPLETED }
        val current = storageService.withCharacterAvatars(
            storageService.loadSessionManifest(runId, sessionId),
            runId,
        )
        return buildJsonObject {
            put("operation_id", receipt.operationId)
            put("turn_id", receipt.turnId)
            put("status", receipt.status.name.lowercase())
            put("base_revision", receipt.baseRevision)
            put("transcript_count", transcriptSize(current))
            put("failure_code", receipt.failure?.code.orEmpty())
            put("failure_message", receipt.failure?.message.orEmpty())
            put("retryable", receipt.failure?.retryable == true)
            if (result != null) {
                put("committed_revision", result.committedRevision)
                put("session", leanSession(current))
                put("appended_transcript", buildJsonArray {
                    result.appendedTranscript.forEach { add(it.withoutWorldBookTimingDelta()) }
                })
            }
        }
    }

    fun responseSession(outcome: DialogueReplyOutcome, includeTranscript: Boolean): JsonObject {
        val session = if (includeTranscript) {
            storageService.getDialogueSession(outcome.sessionManifest["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty(), outcome.sessionManifest["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
        } else {
            outcome.sessionManifest
        }
        return storageService.withCharacterAvatars(session, outcome.sessionManifest["run_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
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
        pacing: String = "normal",
        speakerOverride: String = "",
        includeInnerThoughts: Boolean = false,
        operationId: String = "",
        suppressTranscriptMessage: Boolean = false,
        includeModelReasoning: Boolean = false,
        operation: ActiveReplyOperation? = null,
        onCompleted: suspend (DialogueReplyOutcome) -> Unit = {},
        onPromptPreview: (suspend (DialoguePromptPreviewDto) -> Unit)? = null,
    ): Flow<StreamEvent> = flow {
        val startedAt = nowEpochMillis()
        PlatformLog.d(TAG, "Starting streaming dialogue reply: run=$runId, session=$sessionId")

        // 1. 加载模型设置
        val modelSettings = storageService.loadModelSettings()
        val modelName = modelSettings["model"] as? String ?: "gpt-4"
        val settingsLoadedAt = nowEpochMillis()

        // 2. 构建提示词（对齐 Python build_dialogue_llm_messages 完整管道）
        val payloadBuilder = DialoguePayloadBuilder(storageService, projections)
        val promptBuilder = DialoguePromptBuilder(promptLoader)
        val runManifest = storageService.readRunManifest(runId)
            ?: throw NoSuchElementException("Run not found: $runId")
        val turnId = operation?.receipt?.turnId ?: operationId.ifBlank { randomUuid() }
        val sessionManifestJson = dialogue.prepareSessionForGeneration(runId, sessionId, turnId, message)
        val manifestsLoadedAt = nowEpochMillis()
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
        val payload = dialogue.enrichHistory(
            runId = runId,
            sessionId = sessionId,
            turnId = turnId,
            message = message,
            baseRevision = operation?.receipt?.baseRevision ?: sessionHistoryRevision(sessionManifestJson),
            sessionManifest = sessionManifestJson,
            payload = basePayload,
        )
        val payloadBuiltAt = nowEpochMillis()

        // 3. 构建对话历史
        val conversationHistory = DialogueService.applyPacingInstruction(
            messages = promptBuilder.buildDialogueLlmMessages(payload = payload, retryOnEmpty = false),
            pacing = pacing,
        )
        val promptBuiltAt = nowEpochMillis()
        val promptChars = conversationHistory.sumOf { it.content?.length ?: 0 }

        // 4. 计算 max_tokens（对齐 Python：推理模型 reasoning_content 计入预算，默认至少 8192）
        val responseLimit = ((payload["host_action"] as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?.get("response_limit_hint") as? Number)?.toInt() ?: 0
        val maxTokens = DialogueService.resolveDialogueMaxTokens(
            responseLimitHint = responseLimit,
            reasoningEffort = modelSettings["reasoning_effort"] as? String ?: "auto",
            pacing = pacing,
        )

        // Apply the same speaker contract while streaming that is used for the final parse.
        // Otherwise a model response for the user-controlled character is briefly rendered and
        // then disappears when the committed transcript correctly filters it out.
        val inputMap = (payload["input"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
        val participants = ((inputMap["allowed_responders"] as? List<*>) ?: emptyList<Any?>())
            .mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() } + listOf("旁白", "场景提示")
        val forbidden = listOf(
            inputMap["controlled_character"]?.toString()?.trim().orEmpty(),
            inputMap["speaker"]?.toString()?.trim().orEmpty(),
        )

        // 5. 创建流式解析器（密钥由 LlmClient 从活动模型配置与 Keystore 解析，无需在此获取）
        val parser = DialogueStreamParser(chunkSize = 24)
        val modelStartedAt = nowEpochMillis()
        var firstContentAt: Long? = null

        // 6. 调用 LLM 流式 API
        try {
            onPromptPreview?.invoke(dialoguePromptPreview(turnId, 1, conversationHistory, dialogue.extractWorldBookMatches(payload)))
            llmClient.chatCompletionStream(
                messages = conversationHistory,
                model = modelName,
                temperature = 0.7,
                maxTokens = maxTokens,
                // 请求参数（thinking / reasoning_effort）由模型设置的 reasoning_effort 决定（对齐 Python _apply_reasoning_controls）；
                // enableReasoning 仅控制下方 onReasoning 是否把推理过程透传为 model_reasoning delta，不影响模型是否思考
                enableReasoning = includeModelReasoning,
                requireJsonObject = false,
                context = LlmRequestContext(
                    runId = runId,
                    sessionId = sessionId,
                    turnId = turnId,
                    operationId = operation?.receipt?.operationId ?: operationId,
                    attemptKind = "initial",
                ),
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
                filterDialogueStreamEvents(parser.feed(contentDelta), participants, forbidden).forEach { emit(it) }
            }
            val firstModelCompletedAt = nowEpochMillis()

            // 7. 流结束：从完整输出解析 responses 并提交 turn（对齐 Python generate_and_commit）
            val full = parser.fullContent()
            // 解析失败重试一次（对齐 Python generate_dialogue_responses 的 0..1 重试循环：
            // retryOnEmpty 提示词 + maxTokens 翻倍到上限；第二次仍失败则抛错 → SSE error）。
            // 此处不做单角色纯文本兜底：那会静默丢掉 speaker，让错误数据进入 transcript。
            var responses: List<DialogueResponse>
            try {
                responses = DialogueResponseParser.parse(full, participants, forbidden)
            } catch (e: IllegalArgumentException) {
                // 解析失败不再退化成“单角色纯文本”：那种兜底会把多角色回合压成一条无名回复，
                // 说话人被丢弃、后续回合的上下文也随之错乱。统一走一次重试，仍失败则向外抛错。
                PlatformLog.e(
                    TAG,
                    "Streaming reply parse failed (attempt 1/2): ${e.message}" +
                        "\n--- full output (first 800 chars) ---\n${full.take(800)}" +
                        "\n--- participants: $participants / forbidden: $forbidden ---",
                )
                val retryHistory = DialogueService.applyPacingInstruction(
                    promptBuilder.buildDialogueLlmMessages(payload, retryOnEmpty = true),
                    pacing,
                )
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
                onPromptPreview?.invoke(dialoguePromptPreview(turnId, 2, retryHistory, dialogue.extractWorldBookMatches(payload)))
                val retryStartedAt = nowEpochMillis()
                var retryFirstContentAt: Long? = null
                llmClient.chatCompletionStream(
                    messages = retryHistory,
                    model = modelName,
                    temperature = 0.7,
                    maxTokens = retryMaxTokens,
                    enableReasoning = includeModelReasoning,
                    requireJsonObject = false,
                    context = LlmRequestContext(
                        runId = runId,
                        sessionId = sessionId,
                        turnId = turnId,
                        operationId = operation?.receipt?.operationId ?: operationId,
                        attemptKind = "format-retry",
                    ),
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
                    filterDialogueStreamEvents(
                        retryParser.feed(contentDelta),
                        participants,
                        forbidden,
                    ).forEach { emit(it) }
                }
                val retryFull = retryParser.fullContent().trim()
                PlatformLog.d(
                    TAG,
                    "Streaming reply retry (attempt 2/2) returned in ${nowEpochMillis() - retryStartedAt}ms " +
                        "(ttft_ms=${retryFirstContentAt?.minus(retryStartedAt) ?: -1}): ${retryFull.take(200)}",
                )
                responses = try {
                    DialogueResponseParser.parse(retryFull, participants, forbidden)
                } catch (retryError: IllegalArgumentException) {
                    PlatformLog.e(
                        TAG,
                        "Streaming reply parse failed (attempt 2/2): ${retryError.message}" +
                            "\n--- retry output (first 800 chars) ---\n${retryFull.take(800)}",
                    )
                    throw IllegalStateException(
                        "模型回复格式无法解析，已自动重试 1 次仍失败：${retryError.message}",
                        retryError,
                    )
                }
            }
            val parsedAt = nowEpochMillis()
            val commit = dialogue.commitTurnDetailed(
                runId = runId,
                sessionId = sessionId,
                turnId = turnId,
                message = message,
                messageKind = messageKind,
                inputSpeaker = inputMap["speaker"]?.toString().orEmpty(),
                inputRole = if (speakerOverride.isNotBlank()) "character" else "user",
                responses = responses,
                suppressTranscriptMessage = suppressTranscriptMessage,
                existingSession = sessionManifestJson,
                evidence = dialogue.extractOriginalEvidence(payload),
                worldBookMatches = dialogue.extractWorldBookMatches(payload),
                worldBookTimingDelta = payload[WorldBookTiming.DELTA] as? JsonObject,
                operation = operation,
            )
            val outcome = if (operations != null && operation != null) {
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
            onCompleted(outcome)
            val committedAt = nowEpochMillis()
            PlatformLog.d(
                TAG,
                "Streaming dialogue reply completed and committed: total_ms=${committedAt - startedAt}, " +
                    "settings_ms=${settingsLoadedAt - startedAt}, manifests_ms=${manifestsLoadedAt - settingsLoadedAt}, " +
                    "payload_ms=${payloadBuiltAt - manifestsLoadedAt}, prompt_ms=${promptBuiltAt - payloadBuiltAt}, " +
                    "first_model_ms=${firstModelCompletedAt - modelStartedAt}, parse_retry_ms=${parsedAt - firstModelCompletedAt}, " +
                    "commit_ms=${committedAt - parsedAt}, prompt_chars=$promptChars",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Error during streaming dialogue reply after ${nowEpochMillis() - startedAt}ms", e)
            throw e
        }
    }
}

internal fun filterDialogueStreamEvents(
    events: List<StreamEvent>,
    allowedSpeakers: List<String>,
    forbiddenSpeakers: List<String>,
): List<StreamEvent> {
    val allowedByNormalized = allowedSpeakers
        .map(String::trim)
        .filter(String::isNotEmpty)
        .associateBy(::normalizeDialogueSpeaker)
    val forbidden = forbiddenSpeakers
        .map(::normalizeDialogueSpeaker)
        .filter(String::isNotEmpty)
        .toSet()
    return events.mapNotNull { event ->
        val normalized = normalizeDialogueSpeaker(event.speaker)
        if (normalized.isEmpty() || normalized in forbidden) return@mapNotNull null
        val canonical = allowedByNormalized[normalized] ?: return@mapNotNull null
        if (event.speaker == canonical) event else event.copy(speaker = canonical)
    }
}

private fun normalizeDialogueSpeaker(value: String): String =
    value.filterNot(Char::isWhitespace).lowercase()

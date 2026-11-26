package top.wkbin.zaomeng.feature.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.ApiRequestException
import top.wkbin.zaomeng.data.DialogueRepository
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.DialogueStreamEvent
import top.wkbin.zaomeng.data.api.TranscriptItemDto
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

/**
 * Owns one streaming send: cancellation, delta batching, completion reconciliation,
 * and recovery after an interrupted connection.
 */
internal class ChatStreamEngine(
    private val dialogue: DialogueRepository,
    private val sessions: SessionRepository,
    private val scope: CoroutineScope,
    private val currentState: () -> ChatUiState,
    private val updateState: (((ChatUiState) -> ChatUiState) -> Unit),
) {
    private var sendJob: Job? = null

    fun cancel() {
        sendJob?.cancel()
        sendJob = null
    }

    fun start(
        snapshot: ChatUiState,
        message: String,
        messageKind: String,
        speakerOverride: String = "",
        operationId: String,
        suppressTranscriptMessage: Boolean = messageKind == "plot",
        showPendingUserMessage: Boolean = true,
        onComplete: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null,
    ) {
        cancel()
        sendJob = scope.launch {
            val reasoningBuffer = StringBuilder()
            var reasoningTruncated = false
            var reasoningFinalized = false
            var lastReasoningUpdateAt = TimeSource.Monotonic.markNow()
            val deltaBuffer = ReplyDeltaBuffer()
            var replyDeltaFlushJob: Job? = null

            fun flushReplyDeltas() {
                val batch = deltaBuffer.drain()
                if (batch.isEmpty()) return
                updateSendState(snapshot, operationId) { current ->
                    val repliesByIndex = current.streamingReplies
                        .associateBy(StreamingReplyPart::index)
                        .toMutableMap()
                    batch.forEach { event ->
                        val existing = repliesByIndex[event.index]
                        repliesByIndex[event.index] = if (event.field == "inner_thought") {
                            StreamingReplyPart(
                                index = event.index,
                                speaker = event.speaker.ifBlank { existing?.speaker.orEmpty() },
                                role = event.role.ifBlank { existing?.role ?: "character" },
                                text = existing?.text.orEmpty(),
                                innerThought = existing?.innerThought.orEmpty() + event.text,
                            )
                        } else {
                            StreamingReplyPart(
                                index = event.index,
                                speaker = event.speaker.ifBlank { existing?.speaker.orEmpty() },
                                role = event.role.ifBlank { existing?.role ?: "character" },
                                text = existing?.text.orEmpty() + event.text,
                                innerThought = existing?.innerThought.orEmpty(),
                            )
                        }
                    }
                    current.copy(
                        pendingUserMessage = current.pendingUserMessage?.copy(
                            statusText = "正在生成回复",
                        ),
                        streamingReplies = repliesByIndex.values.sortedBy(StreamingReplyPart::index),
                    )
                }
            }

            fun queueReplyDelta(event: DialogueStreamEvent.Delta) {
                if (deltaBuffer.enqueue(event)) {
                    flushReplyDeltas()
                    return
                }
                if (replyDeltaFlushJob?.isActive == true) return
                // 挂在本次 send 协程下（而不是 viewModelScope），cancel() 取消 sendJob 时一并停掉，
                // 避免取消后仍有延迟冲刷把 delta 写回已废弃的流。
                replyDeltaFlushJob = launch {
                    delay(STREAMING_UI_UPDATE_INTERVAL_MS)
                    flushReplyDeltas()
                }
            }

            fun flushReasoning(force: Boolean = false) {
                if (reasoningBuffer.isEmpty() && !reasoningTruncated) return
                val now = TimeSource.Monotonic.markNow()
                if (!force && now - lastReasoningUpdateAt < MODEL_REASONING_UPDATE_INTERVAL_NANOS.nanoseconds) {
                    return
                }
                lastReasoningUpdateAt = now
                val displayText = buildString {
                    append(reasoningBuffer)
                    if (reasoningTruncated) append("\n...")
                }
                updateSendState(snapshot, operationId) { current ->
                    current.copy(
                        modelReasoning = displayText,
                        pendingUserMessage = current.pendingUserMessage?.copy(
                            statusText = "模型正在思考",
                        ),
                    )
                }
            }

            updateState { current ->
                if (current.runId != snapshot.runId || current.sessionId != snapshot.sessionId) {
                    current
                } else {
                    current.copy(
                        sending = true,
                        draft = "",
                        draftSpeakerOverride = "",
                        error = "",
                        modelReasoning = "",
                        captureNextPrompt = false,
                        streamingReplies = emptyList(),
                        sendOutcomeUnknown = false,
                        failedOperationId = operationId,
                        failedMessage = message,
                        failedMessageKind = messageKind,
                        failedSpeakerOverride = speakerOverride,
                        sendBaselineTranscript = snapshot.session?.transcript,
                        pendingUserMessage = if (showPendingUserMessage) {
                            PendingUserMessage(
                                operationId = operationId,
                                message = message,
                                messageKind = messageKind,
                                speakerOverride = speakerOverride,
                                status = PendingUserMessageStatus.Sending,
                                statusText = "正在发送",
                            )
                        } else {
                            null
                        },
                    )
                }
            }
            if (!isCurrent(snapshot, operationId)) return@launch

            try {
                dialogue.streamReply(
                    runId = snapshot.runId,
                    sessionId = snapshot.sessionId,
                    message = message,
                    messageKind = messageKind,
                    operationId = operationId,
                    pacing = snapshot.pacing,
                    speakerOverride = speakerOverride,
                    suppressTranscriptMessage = suppressTranscriptMessage,
                    includeInnerThoughts = snapshot.includeInnerThoughts,
                    includeModelReasoning = snapshot.chatDisplay.showModelReasoning,
                    includePromptPreview = snapshot.captureNextPrompt,
                ).collect { event ->
                    when (event) {
                        is DialogueStreamEvent.PromptPreview -> if (snapshot.captureNextPrompt) {
                            updateSendState(snapshot, operationId) { it.copy(promptPreview = event.preview) }
                        }
                        is DialogueStreamEvent.Status -> updateSendState(snapshot, operationId) {
                            val status = event.message.ifBlank { event.phase }
                            it.copy(
                                pendingUserMessage = it.pendingUserMessage?.copy(statusText = status),
                            )
                        }
                        is DialogueStreamEvent.Delta -> {
                            if (event.field == "model_reasoning") {
                                val remaining = MODEL_REASONING_DISPLAY_LIMIT - reasoningBuffer.length
                                if (remaining > 0) reasoningBuffer.append(event.text.take(remaining))
                                if (event.text.length > remaining.coerceAtLeast(0)) {
                                    reasoningTruncated = true
                                }
                                flushReasoning()
                                return@collect
                            }
                            if (event.field != "inner_thought" && !reasoningFinalized) {
                                flushReasoning(force = true)
                                reasoningFinalized = true
                            }
                            queueReplyDelta(event)
                        }
                        is DialogueStreamEvent.Reset -> {
                            replyDeltaFlushJob?.cancel()
                            deltaBuffer.reset()
                            reasoningBuffer.setLength(0)
                            reasoningTruncated = false
                            reasoningFinalized = false
                            lastReasoningUpdateAt = TimeSource.Monotonic.markNow()
                            updateSendState(snapshot, operationId) {
                                it.copy(modelReasoning = "", streamingReplies = emptyList())
                            }
                        }
                        is DialogueStreamEvent.Complete -> {
                            if (event.operationId.isNotBlank() && event.operationId != operationId) {
                                throw StreamReplyException("完成事件与当前发送标识不一致，请重新核对。", true, outcomeUnknown = true)
                            }
                            replyDeltaFlushJob?.cancel()
                            flushReplyDeltas()
                            if (!reasoningFinalized) flushReasoning(force = true)
                            val baseline = snapshot.session?.transcript.orEmpty()
                            val baselineCount = snapshot.session?.transcriptCount ?: baseline.size
                            var appended = event.appendedTranscript
                            if (event.turnId.isNotBlank() && appended.any {
                                it.turnId.isNotBlank() && it.turnId != event.turnId
                            }) {
                                throw StreamReplyException("本轮结果与消息标识不一致，请重新同步。", true)
                            }
                            val expectedGrowth = event.transcriptCount - baselineCount
                            val revisionMismatch = event.committedRevision != 0L &&
                                event.session.revision != 0L &&
                                event.committedRevision != event.session.revision
                            val baseMismatch = event.baseRevision != 0L &&
                                event.baseRevision != snapshot.session?.revision
                            val incompleteCommittedTurn = !event.replayed && event.committedRevision != 0L &&
                                expectedGrowth != appended.size
                            val resynced = if (baseMismatch || revisionMismatch || incompleteCommittedTurn) {
                                val refreshed = sessions.getSession(
                                    snapshot.runId,
                                    snapshot.sessionId,
                                    includeTranscript = false,
                                )
                                val tail = sessions.listSessionMessages(
                                    snapshot.runId,
                                    snapshot.sessionId,
                                    offset = 0,
                                    limit = baseline.size.coerceIn(100, 500),
                                    order = "desc",
                                    expectedRevision = refreshed.revision,
                                )
                                refreshed.copy(
                                    transcript = tail.items.asReversed(),
                                    transcriptCount = tail.total,
                                    revision = tail.revision,
                                )
                            } else {
                                null
                            }
                            if (resynced == null && event.committedRevision == 0L && expectedGrowth > appended.size) {
                                val missingEnd = event.transcriptCount - appended.size
                                val missing = if (missingEnd > baselineCount) {
                                    fetchTranscriptAppend(
                                        snapshot.runId,
                                        snapshot.sessionId,
                                        baselineCount,
                                        missingEnd,
                                        event.session.revision,
                                    )
                                } else {
                                    emptyList()
                                }
                                appended = missing + appended
                            }
                            updateSendState(snapshot, operationId) { current ->
                                val base = resynced?.transcript ?: current.session?.transcript ?: baseline
                                val merged = if (resynced != null) base else mergeTranscript(base, appended)
                                val session = (resynced ?: event.session).copy(
                                    transcript = merged,
                                    transcriptCount = resynced?.transcriptCount ?: event.transcriptCount,
                                )
                                current.copy(
                                    sending = false,
                                    session = session,
                                    earlierNextOffset = session.transcript.size.takeIf { it < session.transcriptCount },
                                    draft = "",
                                    sendOutcomeUnknown = false,
                                    sendBaselineTranscript = null,
                                    failedOperationId = "",
                                    failedMessage = "",
                                    failedSpeakerOverride = "",
                                    streamingReplies = emptyList(),
                                    pendingUserMessage = null,
                                    notice = if (event.replayed) {
                                        "已恢复这次发送的本地结果。"
                                    } else {
                                        current.notice
                                    },
                                    error = "",
                                )
                            }
                            refreshMemoryQuality(snapshot)
                            onComplete?.invoke()
                        }
                        is DialogueStreamEvent.Failure -> throw StreamReplyException(
                            event.message,
                            event.retryable,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                replyDeltaFlushJob?.cancel()
                throw cancelled
            } catch (error: Throwable) {
                replyDeltaFlushJob?.cancel()
                deltaBuffer.reset()
                handleStreamingFailure(
                    snapshot = snapshot,
                    operationId = operationId,
                    message = message,
                    messageKind = messageKind,
                    speakerOverride = speakerOverride,
                    error = error,
                    showPendingUserMessage = showPendingUserMessage,
                )
                onFailure?.invoke()
            }
        }
    }

    suspend fun resolveCommittedAppend(
        runId: String,
        sessionId: String,
        baselineCount: Int,
        current: DialogueSessionDto,
    ): List<TranscriptItemDto> {
        if (current.status != "ready") return emptyList()
        val count = current.transcriptCount
        if (count <= baselineCount) return emptyList()
        if (current.transcript.isNotEmpty() && current.transcript.size == count) {
            return committedAppend(baselineCount, current.transcript)
        }
        return fetchTranscriptAppend(runId, sessionId, baselineCount, count, current.revision)
            .takeIf(::hasCommittedContent)
            .orEmpty()
    }

    private suspend fun fetchTranscriptAppend(
        runId: String,
        sessionId: String,
        fromCount: Int,
        totalCount: Int,
        expectedRevision: Long? = null,
    ): List<TranscriptItemDto> {
        if (fromCount >= totalCount) return emptyList()
        val items = mutableListOf<TranscriptItemDto>()
        var offset: Int? = fromCount
        while (offset != null && offset < totalCount) {
            val currentOffset = offset
            val page = sessions.listSessionMessages(
                runId,
                sessionId,
                offset = currentOffset,
                limit = (totalCount - currentOffset).coerceIn(1, 500),
                order = "asc",
                expectedRevision = expectedRevision,
            )
            if (expectedRevision != null && page.revision != expectedRevision) {
                throw StreamReplyException("会话已更新，请重新同步消息。", true)
            }
            items += page.items
            if (page.items.isEmpty() || !page.hasMore) break
            offset = page.nextOffset ?: (currentOffset + page.items.size)
        }
        return items
    }

    private suspend fun handleStreamingFailure(
        snapshot: ChatUiState,
        operationId: String,
        message: String,
        messageKind: String,
        speakerOverride: String,
        error: Throwable,
        showPendingUserMessage: Boolean,
    ) {
        val baseline = snapshot.session?.transcript.orEmpty()
        val baselineCount = snapshot.session?.transcriptCount ?: baseline.size
        val terminalConflict = error is ApiRequestException && error.errorCode in setOf(
            "OPERATION_ID_CONFLICT",
            "SESSION_REVISION_CONFLICT",
        )
        val operation = if (!terminalConflict && operationId.isNotBlank()) {
            try {
                sessions.getSessionOperation(snapshot.runId, snapshot.sessionId, operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        val refreshed = try {
            operation?.session ?: sessions.getSession(snapshot.runId, snapshot.sessionId, includeTranscript = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        var recoveryFailed = false
        val committed = when {
            terminalConflict -> emptyList()
            operation?.status == "completed" -> operation.appendedTranscript
            operation != null -> emptyList()
            refreshed != null -> try {
                resolveCommittedAppend(snapshot.runId, snapshot.sessionId, baselineCount, refreshed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                recoveryFailed = true
                emptyList()
            }
            else -> emptyList()
        }
        var responseWasCommitted = !terminalConflict && when {
            operation?.status == "completed" -> operation.session != null
            operation != null -> false
            else -> refreshed?.status == "ready" && committed.isNotEmpty()
        }
        val needsWindow = refreshed != null && (
            responseWasCommitted || snapshot.session?.revision != refreshed.revision
        )
        val recoveredWindow = if (needsWindow) {
            try {
                sessions.listSessionMessages(
                    snapshot.runId,
                    snapshot.sessionId,
                    offset = 0,
                    limit = baseline.size.coerceIn(100, 500),
                    order = "desc",
                    expectedRevision = refreshed.revision,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                recoveryFailed = true
                responseWasCommitted = false
                null
            }
        } else {
            null
        }
        val retryable = when {
            operation?.status == "failed" -> operation.retryable
            error is StreamReplyException -> error.retryable
            error is ApiRequestException && error.errorCode == "OPERATION_IN_PROGRESS" -> true
            error is ApiRequestException && terminalConflict -> false
            error is ApiRequestException -> error.statusCode == null ||
                error.statusCode == 408 || error.statusCode == 429 || (error.statusCode ?: 0) >= 500
            else -> true
        }
        val outcomeUnknown = !responseWasCommitted && retryable &&
            (recoveryFailed || operation?.status !in setOf("failed", "completed")) &&
            (recoveryFailed || error !is StreamReplyException || error.outcomeUnknown)
        updateSendState(snapshot, operationId) { current ->
            val failureText = if (responseWasCommitted) {
                ""
            } else {
                operation?.failureMessage?.takeIf(String::isNotBlank) ?: error.readableMessage(
                    if (retryable) "回复生成失败，可安全重试这次发送。" else "回复请求失败，请检查消息或模型配置。",
                )
            }
            val restoredSession = when {
                recoveryFailed -> current.session
                refreshed == null -> current.session
                recoveredWindow != null -> refreshed.copy(
                    transcript = recoveredWindow.items.asReversed(),
                    transcriptCount = recoveredWindow.total,
                    revision = recoveredWindow.revision,
                )
                else -> refreshed.copy(transcript = baseline)
            }
            current.copy(
                sending = false,
                session = restoredSession,
                earlierNextOffset = restoredSession?.let { session ->
                    session.transcript.size.takeIf { it < session.transcriptCount }
                },
                draft = if (responseWasCommitted) "" else current.draft,
                sendOutcomeUnknown = outcomeUnknown,
                sendBaselineTranscript = if (outcomeUnknown) baseline else null,
                failedOperationId = if (responseWasCommitted || !showPendingUserMessage) "" else operationId,
                failedMessage = if (responseWasCommitted || !showPendingUserMessage) "" else message,
                failedMessageKind = messageKind,
                failedSpeakerOverride = if (responseWasCommitted || !showPendingUserMessage) "" else speakerOverride,
                streamingReplies = emptyList(),
                pendingUserMessage = if (responseWasCommitted || !showPendingUserMessage) {
                    null
                } else {
                    PendingUserMessage(
                        operationId = operationId,
                        message = message,
                        messageKind = messageKind,
                        speakerOverride = speakerOverride,
                        status = if (outcomeUnknown) PendingUserMessageStatus.OutcomeUnknown else PendingUserMessageStatus.Failed,
                        statusText = failureText,
                        retryable = retryable,
                    )
                },
                error = if (responseWasCommitted) "连接中断，但已从本地恢复最新回复。" else failureText,
            )
        }
    }

    private suspend fun refreshMemoryQuality(snapshot: ChatUiState) {
        runCatching {
            dialogue.getDialogueMemoryQuality(snapshot.runId, snapshot.sessionId)
        }.getOrNull()?.let { report ->
            updateState { current ->
                if (current.runId == snapshot.runId && current.sessionId == snapshot.sessionId) {
                    current.copy(memoryQuality = report)
                } else {
                    current
                }
            }
        }
    }

    private fun isCurrent(snapshot: ChatUiState, operationId: String): Boolean {
        return currentState().matchesSend(snapshot, operationId)
    }

    private fun updateSendState(
        snapshot: ChatUiState,
        operationId: String,
        transform: (ChatUiState) -> ChatUiState,
    ) {
        updateState { current ->
            if (current.matchesSend(snapshot, operationId)) {
                transform(current)
            } else {
                current
            }
        }
    }
}

/** Mutable buffer kept separate so first-delta behavior can be tested without repositories. */
internal class ReplyDeltaBuffer {
    private val pending = mutableListOf<DialogueStreamEvent.Delta>()
    private var displayedFirstDelta = false

    fun enqueue(event: DialogueStreamEvent.Delta): Boolean {
        pending += event
        if (displayedFirstDelta) return false
        displayedFirstDelta = true
        return true
    }

    fun drain(): List<DialogueStreamEvent.Delta> {
        if (pending.isEmpty()) return emptyList()
        return pending.toList().also { pending.clear() }
    }

    fun reset() {
        pending.clear()
        displayedFirstDelta = false
    }
}

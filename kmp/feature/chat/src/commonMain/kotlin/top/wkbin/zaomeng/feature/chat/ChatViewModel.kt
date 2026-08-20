package top.wkbin.zaomeng.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.data.ReusableCardKind
import top.wkbin.zaomeng.data.ApiRequestException
import top.wkbin.zaomeng.data.CardRepository
import top.wkbin.zaomeng.data.DialogueRepository
import top.wkbin.zaomeng.data.PluginRepository
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.api.DialogueMemoryDto
import top.wkbin.zaomeng.data.api.MemoryQualityReportDto
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.DialogueStreamEvent
import top.wkbin.zaomeng.data.api.ChatSearchResultDto
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.data.api.TranscriptItemDto
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.ChatDisplayPreferences
import top.wkbin.zaomeng.domain.chat.LoadChatSessionUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource
import top.wkbin.zaomeng.client.platform.clientRandomUuid

class ChatViewModel(
    private val dialogue: DialogueRepository,
    private val sessions: SessionRepository,
    private val plugins: PluginRepository,
    private val cards: CardRepository,
    preferencesRepository: AppPreferencesRepository,
    private val loadChatSession: LoadChatSessionUseCase,
) : ViewModel() {
    private val toolsDelegate = ChatToolsDelegate(sessions)
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var pluginActionsJob: Job? = null
    private var loadRequestId = 0L
    private var toolJob: Job? = null
    private var nextToolRequestId = 0L
    private var activeToolRequest: ToolRequest? = null
    private var sendJob: Job? = null
    private var continuousObserveJob: Job? = null
    private var continuousObserveSessionId = ""
    private var searchJob: Job? = null
    private var recoveryJob: Job? = null
    private var nextRecoveryRequestId = 0L

    init {
        viewModelScope.launch {
            preferencesRepository.chatDisplayPreferences.collect { preferences ->
                mutableState.update { it.copy(chatDisplay = preferences) }
            }
        }
    }

    private data class ToolRequest(
        val id: Long,
        val snapshot: ChatUiState,
    ) {
        val runId: String get() = snapshot.runId
        val sessionId: String get() = snapshot.sessionId
    }

    fun load(runId: String, sessionId: String, force: Boolean = false) {
        val normalizedRunId = runId.trim()
        val normalizedSessionId = sessionId.trim()
        val current = state.value
        if (
            !force &&
            !current.loading &&
            current.runId == normalizedRunId &&
            current.sessionId == normalizedSessionId
        ) {
            return
        }
        if (normalizedRunId.isBlank() || normalizedSessionId.isBlank()) {
            cancelLoadRequest()
            cancelToolRequest()
            sendJob?.cancel()
            stopContinuousObserve(notice = "")
            searchJob?.cancel()
            cancelRecoveryRequest()
            mutableState.value = ChatUiState(
                runId = normalizedRunId,
                sessionId = normalizedSessionId,
                loading = false,
                chatDisplay = current.chatDisplay,
                error = "会话地址不完整，无法打开聊天。",
            )
            return
        }

        cancelLoadRequest()
        cancelToolRequest()
        val targetChanged = current.runId != normalizedRunId ||
            current.sessionId != normalizedSessionId
        if (targetChanged || force) {
            sendJob?.cancel()
            stopContinuousObserve(notice = "")
            searchJob?.cancel()
            cancelRecoveryRequest()
        }
        val requestId = ++loadRequestId
        mutableState.update {
            if (targetChanged) {
                ChatUiState(
                    runId = normalizedRunId,
                    sessionId = normalizedSessionId,
                    loading = true,
                    chatDisplay = current.chatDisplay,
                )
            } else {
                it.copy(
                    loading = it.session == null,
                    refreshing = it.session != null,
                    toolBusy = "",
                    modelReasoning = "",
                    error = "",
                )
            }
        }
        loadJob = viewModelScope.launch {
            try {
                val loaded = loadChatSession(
                    runId = normalizedRunId,
                    sessionId = normalizedSessionId,
                    transcriptPageSize = INITIAL_TRANSCRIPT_PAGE,
                )
                val session = loaded.session
                val plugins = loadChatPlugins()
                updateLoadState(requestId, normalizedRunId, normalizedSessionId) {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        session = session,
                        runSessions = loaded.runSessions,
                        avatarBytes = loaded.avatarBytes,
                        pluginActions = plugins.actions,
                        generationEnhancers = plugins.enhancers,
                        memoryQuality = loaded.memoryQuality,
                        includeInnerThoughts = plugins.enhancers.any { enhancer ->
                            enhancer.stateKey == INNER_THOUGHTS_ENHANCER_KEY &&
                                enhancer.isActive(session)
                        },
                        error = "",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateLoadState(requestId, normalizedRunId, normalizedSessionId) {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = error.readableMessage("会话加载失败，请稍后重试。"),
                    )
                }
            }
        }
    }

    /** 向上加载更早的历史消息（本地 transcript 只保留尾部 + 增量，历史按需取页）。 */
    fun loadEarlierMessages() {
        val snapshot = state.value
        val session = snapshot.session ?: return
        if (
            snapshot.loadingEarlier ||
            snapshot.loading ||
            snapshot.refreshing ||
            snapshot.sending ||
            snapshot.recovering ||
            snapshot.sendOutcomeUnknown
        ) {
            return
        }
        val loaded = session.transcript.size
        val total = session.transcriptCount
        if (loaded >= total) return
        // order=desc：offset 表示跳过最新 N 条，返回更早一页（新→旧），反转后向上追加
        val offset = total - loaded
        viewModelScope.launch {
            mutableState.update { it.copy(loadingEarlier = true, error = "") }
            try {
                val page = sessions.listSessionMessages(
                    snapshot.runId,
                    snapshot.sessionId,
                    offset = offset,
                    limit = EARLIER_TRANSCRIPT_PAGE,
                    order = "desc",
                )
                mutableState.update { current ->
                    val currentSession = current.session ?: return@update current
                    current.copy(
                        session = currentSession.copy(
                            transcript = page.items.asReversed() + currentSession.transcript,
                            transcriptCount = page.total,
                        ),
                        loadingEarlier = false,
                        error = "",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loadingEarlier = false,
                        error = error.readableMessage("更早消息加载失败，请稍后重试。"),
                    )
                }
            }
        }
    }

    private suspend fun loadChatPlugins(): LoadedChatPlugins {
        return try {
            val plugins = plugins.listPlugins()
                .filter { plugin -> plugin.executable && plugin.enabled && plugin.status == "enabled" }
            val actions = plugins.asSequence()
                .flatMap { plugin ->
                    plugin.contributes.chatActions.asSequence()
                        .filter { action -> action.placement == "composer" }
                        .map { action ->
                            ChatPluginAction(
                                pluginId = plugin.id,
                                pluginName = plugin.name,
                                actionId = action.id,
                                title = action.title,
                                icon = action.icon,
                            )
                        }
                }
                .plus(
                    plugins.asSequence().flatMap { plugin ->
                        plugin.contributes.temporaryNpcGenerators.asSequence().map { generator ->
                            ChatPluginAction(
                                pluginId = plugin.id,
                                pluginName = plugin.name,
                                actionId = generator.id,
                                title = generator.title,
                                icon = generator.icon,
                                contribution = "temporary_npc_generator",
                            )
                        }
                    }
                )
                .filter { action -> action.pluginId.isNotBlank() && action.actionId.isNotBlank() }
                .toList()
            val enhancers = plugins.asSequence()
                .flatMap { plugin ->
                    plugin.contributes.generationEnhancers.asSequence().map { enhancer ->
                        ChatGenerationEnhancer(
                            pluginId = plugin.id,
                            pluginName = plugin.name,
                            enhancerId = enhancer.id,
                            title = enhancer.title,
                            description = enhancer.description,
                            icon = enhancer.icon,
                            defaultActive = enhancer.defaultActive,
                        )
                    }
                }
                .filter { enhancer ->
                    enhancer.pluginId.isNotBlank() && enhancer.enhancerId.isNotBlank()
                }
                .toList()
            LoadedChatPlugins(actions = actions, enhancers = enhancers)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            LoadedChatPlugins()
        }
    }

    fun refreshPluginActions() {
        pluginActionsJob?.cancel()
        pluginActionsJob = viewModelScope.launch {
            try {
                val plugins = loadChatPlugins()
                mutableState.update { current ->
                    current.copy(
                        pluginActions = plugins.actions,
                        generationEnhancers = plugins.enhancers,
                        includeInnerThoughts = plugins.enhancers.any { enhancer ->
                            enhancer.stateKey == INNER_THOUGHTS_ENHANCER_KEY &&
                                enhancer.isActive(current.session)
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Plugin availability should not prevent an existing chat from opening.
            }
        }
    }

    fun refresh() {
        val current = state.value
        if (current.canRefresh) {
            if (current.sendOutcomeUnknown) {
                reconcileUnknownSend()
            } else {
                load(current.runId, current.sessionId, force = true)
            }
        }
    }

    fun updateDraft(value: String) {
        if (state.value.sendOutcomeUnknown || state.value.failedOperationId.isNotBlank()) return
        mutableState.update {
            it.copy(
                draft = value,
                draftSpeakerOverride = if (value.isBlank()) "" else it.draftSpeakerOverride,
                error = "",
                notice = "",
            )
        }
    }

    fun clearDraftSpeakerOverride() {
        if (state.value.sending || state.value.failedOperationId.isNotBlank()) return
        mutableState.update {
            it.copy(
                draftSpeakerOverride = "",
                notice = "已取消人物代发，将恢复为当前受控人物发送。",
            )
        }
    }

    fun updateSearchQuery(value: String) {
        val query = value.take(120)
        val snapshot = state.value
        searchJob?.cancel()
        mutableState.update {
            it.copy(
                searchQuery = query,
                searching = query.isNotBlank(),
                searchResults = if (query.isBlank()) emptyList() else it.searchResults,
            )
        }
        if (query.isBlank() || snapshot.runId.isBlank() || snapshot.sessionId.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(250)
            try {
                val results = dialogue.searchSession(snapshot.runId, snapshot.sessionId, query)
                if (state.value.searchQuery == query) {
                    mutableState.update { it.copy(searching = false, searchResults = results) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (state.value.searchQuery == query) {
                    val fallback = snapshot.session?.transcript.orEmpty()
                        .filter { item ->
                            item.message.contains(query, ignoreCase = true) ||
                                item.speaker.contains(query, ignoreCase = true)
                        }
                        .map { item ->
                            ChatSearchResultDto(
                                speaker = item.speaker,
                                message = item.message,
                                role = item.role,
                                turnId = item.turnId,
                                timestamp = item.timestamp,
                            )
                        }
                    mutableState.update {
                        it.copy(
                            searching = false,
                            searchResults = fallback,
                            error = if (fallback.isEmpty()) {
                                error.readableMessage("聊天记录搜索失败，请稍后重试。")
                            } else {
                                ""
                            },
                        )
                    }
                }
            }
        }
    }

    fun selectMessageKind(kind: String) {
        val current = state.value
        if (
            kind !in messageKinds || current.sending || current.recovering ||
            current.sendOutcomeUnknown || current.failedOperationId.isNotBlank() ||
            current.toolBusy.isNotBlank()
        ) return
        mutableState.update { it.copy(messageKind = kind, error = "") }
    }

    fun selectPacing(pacing: String) {
        val current = state.value
        if (
            pacing !in pacingOptions || current.sending || current.recovering ||
            current.sendOutcomeUnknown || current.failedOperationId.isNotBlank() ||
            current.toolBusy.isNotBlank()
        ) return
        mutableState.update { it.copy(pacing = pacing, error = "") }
    }

    fun toggleGenerationEnhancer(enhancer: ChatGenerationEnhancer) {
        val current = state.value
        if (!current.canUseTools) return
        val enabled = !enhancer.isActive(current.session)
        runTool("enhancer:${enhancer.stateKey}") {
            val session = plugins.setGenerationEnhancerState(
                runId = runId,
                sessionId = sessionId,
                pluginId = enhancer.pluginId,
                enhancerId = enhancer.enhancerId,
                enabled = enabled,
            )
            updateState {
                it.copy(
                    session = session,
                    includeInnerThoughts = if (
                        enhancer.stateKey == INNER_THOUGHTS_ENHANCER_KEY
                    ) enabled else it.includeInnerThoughts,
                    notice = "「${enhancer.title}」已${if (enabled) "开启" else "关闭"}，仅对当前聊天生效。",
                )
            }
        }
    }

    fun send() {
        val snapshot = state.value
        if (
            snapshot.loading || snapshot.refreshing || snapshot.sending || snapshot.recovering ||
            snapshot.toolBusy.isNotBlank() || snapshot.continuousObserveEnabled
        ) return
        if (snapshot.sendOutcomeUnknown) {
            mutableState.update { it.copy(error = "先核对上一次发送结果，再继续聊天。") }
            return
        }
        val message = snapshot.draft.trim()
        if (message.isBlank()) {
            mutableState.update { it.copy(error = "先写点什么，再把这一句送进故事。") }
            return
        }
        if (snapshot.session?.status != "ready") {
            mutableState.update {
                it.copy(error = "当前会话还有一轮待处理，请刷新后再发送。")
            }
            return
        }

        startStreamingSend(
            snapshot = snapshot,
            message = message,
            messageKind = snapshot.messageKind,
            speakerOverride = snapshot.draftSpeakerOverride,
            operationId = clientRandomUuid(),
        )
    }

    fun toggleContinuousObserve() {
        val snapshot = state.value
        if (snapshot.continuousObserveEnabled) {
            stopContinuousObserve("已暂停连续旁观。")
            return
        }
        if (!snapshot.canToggleContinuousObserve) {
            mutableState.update { it.copy(error = "仅可在就绪的旁观会话中开启连续旁观。") }
            return
        }
        continuousObserveSessionId = snapshot.sessionId
        mutableState.update {
            it.copy(
                continuousObserveEnabled = true,
                notice = "连续旁观已开启。",
                error = "",
            )
        }
        startContinuousObserveRound()
    }

    fun pauseContinuousObserve() {
        stopContinuousObserve(notice = "")
    }

    private fun startContinuousObserveRound() {
        val snapshot = state.value
        val session = snapshot.session
        if (
            !snapshot.continuousObserveEnabled ||
            snapshot.sessionId != continuousObserveSessionId ||
            session?.mode != "observe" ||
            session.status != "ready" ||
            snapshot.sending
        ) {
            if (snapshot.continuousObserveEnabled) {
                stopContinuousObserve("连续旁观已暂停：会话状态已变化。")
            }
            return
        }
        startStreamingSend(
            snapshot = snapshot,
            message = buildContinuousObservePrompt(requireNotNull(session)),
            messageKind = "narration",
            operationId = clientRandomUuid(),
            suppressTranscriptMessage = true,
            showPendingUserMessage = false,
            onComplete = {
                if (
                    state.value.continuousObserveEnabled &&
                    state.value.sessionId == continuousObserveSessionId
                ) {
                    continuousObserveJob?.cancel()
                    continuousObserveJob = viewModelScope.launch {
                        delay(CONTINUOUS_OBSERVE_DELAY_MS)
                        startContinuousObserveRound()
                    }
                }
            },
            onFailure = {
                stopContinuousObserve("连续旁观已暂停：刚才这一轮生成失败。")
            },
        )
    }

    private fun stopContinuousObserve(notice: String) {
        continuousObserveJob?.cancel()
        continuousObserveJob = null
        continuousObserveSessionId = ""
        mutableState.update { current ->
            if (!current.continuousObserveEnabled) current else current.copy(
                continuousObserveEnabled = false,
                notice = notice.ifBlank { current.notice },
            )
        }
    }

    private fun buildContinuousObservePrompt(session: DialogueSessionDto): String =
        ContinuousObserveController.buildPrompt(session)

    fun retryLastSend() {
        val snapshot = state.value
        val pending = snapshot.pendingUserMessage
        if (
            snapshot.failedOperationId.isBlank() || snapshot.failedMessage.isBlank() ||
            snapshot.sending || snapshot.recovering || snapshot.sendOutcomeUnknown ||
            pending?.status != PendingUserMessageStatus.Failed || !pending.retryable
        ) return
        startStreamingSend(
            snapshot = snapshot,
            message = snapshot.failedMessage,
            messageKind = snapshot.failedMessageKind,
            speakerOverride = snapshot.failedSpeakerOverride,
            operationId = snapshot.failedOperationId,
        )
    }

    private fun startStreamingSend(
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
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            val reasoningBuffer = StringBuilder()
            var reasoningTruncated = false
            var reasoningFinalized = false
            var lastReasoningUpdateAt = TimeSource.Monotonic.markNow()
            val streamEngine = ChatStreamEngine()
            var replyDeltaFlushJob: Job? = null

            fun flushReplyDeltas() {
                val batch = streamEngine.drain()
                if (batch.isEmpty()) return
                updateSendState(snapshot, operationId) { current ->
                    val repliesByIndex = current.streamingReplies.associateBy(StreamingReplyPart::index).toMutableMap()
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
                // The first visible dialogue delta should reach Compose immediately.
                // Later deltas remain frame-batched to avoid excessive recomposition.
                if (streamEngine.enqueue(event)) {
                    flushReplyDeltas()
                    return
                }
                if (replyDeltaFlushJob?.isActive == true) return
                replyDeltaFlushJob = viewModelScope.launch {
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

            mutableState.update { current ->
                if (
                    current.runId != snapshot.runId ||
                    current.sessionId != snapshot.sessionId
                ) {
                    current
                } else current.copy(
                    sending = true,
                    draft = "",
                    draftSpeakerOverride = "",
                    error = "",
                    modelReasoning = "",
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
            if (
                state.value.runId != snapshot.runId ||
                state.value.sessionId != snapshot.sessionId ||
                state.value.failedOperationId != operationId
            ) {
                return@launch
            }
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
                ).collect { event ->
                    when (event) {
                        is DialogueStreamEvent.Status -> updateSendState(snapshot, operationId) {
                            val status = event.message.ifBlank { event.phase }
                            it.copy(
                                pendingUserMessage = it.pendingUserMessage?.copy(statusText = status),
                            )
                        }
                        is DialogueStreamEvent.Delta -> {
                            if (event.field == "model_reasoning") {
                                val remaining = MODEL_REASONING_DISPLAY_LIMIT - reasoningBuffer.length
                                if (remaining > 0) {
                                    reasoningBuffer.append(event.text.take(remaining))
                                }
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
                            streamEngine.reset()
                            reasoningBuffer.setLength(0)
                            reasoningTruncated = false
                            reasoningFinalized = false
                            lastReasoningUpdateAt = TimeSource.Monotonic.markNow()
                            updateSendState(snapshot, operationId) {
                                it.copy(
                                    modelReasoning = "",
                                    streamingReplies = emptyList(),
                                )
                            }
                        }
                        is DialogueStreamEvent.Complete -> {
                            replyDeltaFlushJob?.cancel()
                            flushReplyDeltas()
                            if (!reasoningFinalized) flushReasoning(force = true)
                            val baseline = snapshot.session?.transcript.orEmpty()
                            val baselineCount = snapshot.session?.transcriptCount ?: baseline.size
                            var appended = event.appendedTranscript
                            val expectedGrowth = event.transcriptCount - baselineCount
                            if (expectedGrowth > appended.size) {
                                // 增量缺失（operation_id 缺失 / 他端新增了中间条目）：
                                // 补齐 [baselineCount, transcriptCount - appended.size) 后再拼接本轮增量，避免空洞
                                val missingEnd = event.transcriptCount - appended.size
                                val missing = if (missingEnd > baselineCount) {
                                    fetchTranscriptAppend(
                                        snapshot.runId,
                                        snapshot.sessionId,
                                        baselineCount,
                                        missingEnd,
                                    )
                                } else {
                                    emptyList()
                                }
                                appended = missing + appended
                            }
                            updateSendState(snapshot, operationId) { current ->
                                val base = current.session?.transcript ?: baseline
                                // 按 turn_id 幂等合并：断连重试/补齐时已存在的条目替换而非重复追加，
                                // 避免“整个历史重复一遍”（重新进会话不受影响是因为服务端数据本身没重复）
                                val merged = mergeTranscript(base, appended)
                                // 新格式：流式 complete 始终为轻量会话（transcript_count 由服务端携带）
                                val session = event.session.copy(
                                    transcript = merged,
                                    transcriptCount = event.transcriptCount,
                                )
                                current.copy(
                                    sending = false,
                                    session = session,
                                    draft = "",
                                    sendOutcomeUnknown = false,
                                    sendBaselineTranscript = null,
                                    failedOperationId = "",
                                    failedMessage = "",
                                    failedSpeakerOverride = "",
                                    streamingReplies = emptyList(),
                                    pendingUserMessage = null,
                                    notice = if (event.replayed) "已恢复这次发送的本地结果。" else current.notice,
                                    error = "",
                                )
                            }
                            runCatching {
                                dialogue.getDialogueMemoryQuality(snapshot.runId, snapshot.sessionId)
                            }.getOrNull()?.let { report ->
                                mutableState.update { current ->
                                    if (current.runId == snapshot.runId && current.sessionId == snapshot.sessionId) {
                                        current.copy(memoryQuality = report)
                                    } else {
                                        current
                                    }
                                }
                            }
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
                streamEngine.reset()
                handleStreamingFailure(
                    snapshot,
                    operationId,
                    message,
                    messageKind,
                    speakerOverride,
                    error,
                    showPendingUserMessage,
                )
                onFailure?.invoke()
            }
        }
    }

    /**
     * 判定发送结果并取回新增条目：
     * - 当前会话已是全量 transcript → 直接按 baseline 切片；
     * - 轻量响应 → 从消息分页接口取 baseline 之后的新条目。
     */
    private suspend fun resolveCommittedAppend(
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
        val fetched = fetchTranscriptAppend(runId, sessionId, baselineCount, count)
        return fetched.takeIf(::hasCommittedContent).orEmpty()
    }

    /** 从消息分页接口取 [fromCount, totalCount) 区间的 transcript 条目（正序）。 */
    private suspend fun fetchTranscriptAppend(
        runId: String,
        sessionId: String,
        fromCount: Int,
        totalCount: Int,
    ): List<TranscriptItemDto> {
        if (fromCount >= totalCount) return emptyList()
        val items = mutableListOf<TranscriptItemDto>()
        var offset = fromCount
        while (offset < totalCount) {
            val page = sessions.listSessionMessages(
                runId,
                sessionId,
                offset = offset,
                limit = (totalCount - offset).coerceIn(1, 500),
                order = "asc",
            )
            items += page.items
            if (page.items.isEmpty() || !page.hasMore) break
            offset += page.items.size
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
        showPendingUserMessage: Boolean = true,
    ) {
        val baseline = snapshot.session?.transcript.orEmpty()
        val baselineCount = snapshot.session?.transcriptCount ?: baseline.size
        val refreshed = try {
            sessions.getSession(snapshot.runId, snapshot.sessionId, includeTranscript = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        val committedAppend = if (refreshed != null) {
            resolveCommittedAppend(
                runId = snapshot.runId,
                sessionId = snapshot.sessionId,
                baselineCount = baselineCount,
                current = refreshed,
            )
        } else {
            emptyList()
        }
        val responseWasCommitted = refreshed != null &&
            refreshed.status == "ready" &&
            committedAppend.isNotEmpty()
        val retryable = when (error) {
            is StreamReplyException -> error.retryable
            is ApiRequestException -> error.statusCode == null ||
                error.statusCode == 408 || error.statusCode == 429 || (error.statusCode ?: 0) >= 500
            else -> true
        }
        val outcomeUnknown = !responseWasCommitted &&
            error !is StreamReplyException && retryable
        updateSendState(snapshot, operationId) {
            val failureText = if (responseWasCommitted) {
                ""
            } else {
                error.readableMessage(
                    if (retryable) {
                        "回复生成失败，可安全重试这次发送。"
                    } else {
                        "回复请求失败，请检查消息或模型配置。"
                    },
                )
            }
            it.copy(
                sending = false,
                session = refreshed?.copy(
                    transcript = mergeTranscript(baseline, committedAppend),
                    transcriptCount = refreshed.transcriptCount,
                ) ?: it.session,
                draft = if (responseWasCommitted) "" else it.draft,
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
                        status = if (outcomeUnknown) {
                            PendingUserMessageStatus.OutcomeUnknown
                        } else {
                            PendingUserMessageStatus.Failed
                        },
                        statusText = failureText,
                        retryable = retryable,
                    )
                },
                error = if (responseWasCommitted) {
                    "连接中断，但已从本地恢复最新回复。"
                } else {
                    failureText
                },
            )
        }
    }

    fun discardFailedSend() {
        val snapshot = state.value
        if (
            snapshot.sending || snapshot.recovering || snapshot.sendOutcomeUnknown ||
            snapshot.failedOperationId.isBlank() || snapshot.session?.status != "ready"
        ) {
            return
        }
        mutableState.update {
            it.copy(
                failedOperationId = "",
                failedMessage = "",
                failedSpeakerOverride = "",
                sendBaselineTranscript = null,
                pendingUserMessage = null,
                draft = snapshot.failedMessage,
                draftSpeakerOverride = snapshot.failedSpeakerOverride,
                error = "",
                notice = "已保留输入，可以修改后重新发送。",
            )
        }
    }

    fun recoverPending() {
        val snapshot = state.value
        if (
            snapshot.loading ||
            snapshot.sending ||
            snapshot.recovering ||
            snapshot.session == null ||
            snapshot.session.status == "ready"
        ) {
            return
        }

        cancelLoadRequest()
        cancelRecoveryRequest()
        val requestId = ++nextRecoveryRequestId
        recoveryJob = viewModelScope.launch {
            updateRecoveryState(requestId, snapshot) {
                it.copy(refreshing = false, recovering = true, error = "")
            }
            try {
                val recovered = sessions.recoverSession(
                    snapshot.runId,
                    snapshot.sessionId,
                    force = true,
                )
                val baseline = snapshot.sendBaselineTranscript.orEmpty()
                val baselineCount = snapshot.session.transcriptCount
                val committedAppend = if (snapshot.sendBaselineTranscript != null) {
                    // recover 返回全量 transcript：以权威 count（发送前）判断是否新增
                    if (recovered.status == "ready" && recovered.transcript.size > baselineCount) {
                        committedAppend(baselineCount, recovered.transcript)
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
                val responseWasCommitted = committedAppend.isNotEmpty()
                val resolved = recovered.status == "ready"
                updateRecoveryState(requestId, snapshot) {
                    it.copy(
                        recovering = false,
                        session = recovered.copy(
                            transcript = if (recovered.transcript.isNotEmpty()) {
                                recovered.transcript
                            } else {
                                mergeTranscript(baseline, committedAppend)
                            },
                            transcriptCount = recovered.transcriptCount,
                        ),
                        draft = if (responseWasCommitted) "" else it.draft,
                        sendOutcomeUnknown = snapshot.sendOutcomeUnknown && !resolved,
                        sendBaselineTranscript = if (resolved) null else it.sendBaselineTranscript,
                        failedOperationId = if (resolved) "" else it.failedOperationId,
                        failedMessage = if (resolved) "" else it.failedMessage,
                        failedSpeakerOverride = if (resolved) "" else it.failedSpeakerOverride,
                        pendingUserMessage = when {
                            responseWasCommitted -> null
                            resolved -> it.pendingUserMessage?.copy(
                                status = PendingUserMessageStatus.Failed,
                                statusText = "本地未发现回复，可安全重试",
                                retryable = true,
                            )
                            else -> it.pendingUserMessage
                        },
                        error = if (responseWasCommitted) {
                            "已从本地恢复上一次发送的回复。"
                        } else {
                            ""
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateRecoveryState(requestId, snapshot) {
                    it.copy(
                        recovering = false,
                        error = error.readableMessage("会话恢复失败，请稍后重试。"),
                    )
                }
            }
        }
    }

    fun reconcileUnknownSend() {
        val snapshot = state.value
        if (
            !snapshot.sendOutcomeUnknown ||
            snapshot.loading ||
            snapshot.sending ||
            snapshot.recovering
        ) {
            return
        }

        cancelLoadRequest()
        cancelRecoveryRequest()
        val requestId = ++nextRecoveryRequestId
        recoveryJob = viewModelScope.launch {
            updateRecoveryState(requestId, snapshot) {
                it.copy(refreshing = false, recovering = true, error = "")
            }
            try {
                val refreshed = sessions.getSession(
                    snapshot.runId,
                    snapshot.sessionId,
                    includeTranscript = false,
                )
                val baseline = snapshot.sendBaselineTranscript.orEmpty()
                val baselineCount = snapshot.session?.transcriptCount ?: baseline.size
                val committedAppend = if (snapshot.sendBaselineTranscript != null) {
                    resolveCommittedAppend(
                        runId = snapshot.runId,
                        sessionId = snapshot.sessionId,
                        baselineCount = baselineCount,
                        current = refreshed,
                    )
                } else {
                    emptyList()
                }
                val responseWasCommitted = refreshed.status == "ready" && committedAppend.isNotEmpty()
                val resolved = refreshed.status == "ready"
                updateRecoveryState(requestId, snapshot) {
                    it.copy(
                        recovering = false,
                        session = refreshed.copy(
                            transcript = if (refreshed.transcript.isNotEmpty()) {
                                refreshed.transcript
                            } else {
                                mergeTranscript(baseline, committedAppend)
                            },
                            transcriptCount = refreshed.transcriptCount,
                        ),
                        draft = if (responseWasCommitted) "" else it.draft,
                        sendOutcomeUnknown = !resolved,
                        sendBaselineTranscript = if (resolved) null else it.sendBaselineTranscript,
                        failedOperationId = if (responseWasCommitted) "" else it.failedOperationId,
                        failedMessage = if (responseWasCommitted) "" else it.failedMessage,
                        failedSpeakerOverride = if (responseWasCommitted) "" else it.failedSpeakerOverride,
                        pendingUserMessage = when {
                            responseWasCommitted -> null
                            resolved -> it.pendingUserMessage?.copy(
                                status = PendingUserMessageStatus.Failed,
                                statusText = "本地未发现回复，可安全重试",
                                retryable = true,
                            )
                            else -> it.pendingUserMessage
                        },
                        error = when {
                            responseWasCommitted -> "已从本地恢复上一次发送的回复。"
                            resolved -> "本地没有发现新回复，可以安全重试同一次发送。"
                            else -> "这轮仍在处理中，可以稍后核对或恢复会话。"
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateRecoveryState(requestId, snapshot) {
                    it.copy(
                        recovering = false,
                        error = error.readableMessage("暂时无法核对发送结果，请稍后重试。"),
                    )
                }
            }
        }
    }

    fun loadSceneCards() {
        if (state.value.sceneCards.isNotEmpty() || state.value.toolBusy.isNotBlank()) return
        runTool("scene_cards") {
            val cards = cards.listReusableCards(ReusableCardKind.Scene)
            updateState { it.copy(sceneCards = cards) }
        }
    }

    fun suggestReply(direction: String = "") {
        val current = state.value
        requestSuggestedReply(direction, current.messageKind, current.draft)
    }

    fun invokePluginAction(action: ChatPluginAction) {
        val current = state.value
        invokePluginAction(action, selection = "", seedText = current.draft)
    }

    private fun invokePluginAction(action: ChatPluginAction, selection: String, seedText: String) {
        runTool("plugin:${action.pluginId}:${action.actionId}") {
            if (action.contribution == "temporary_npc_generator") {
                val result = plugins.invokePluginTemporaryNpcGenerator(
                    runId = runId,
                    sessionId = sessionId,
                    pluginId = action.pluginId,
                    generatorId = action.actionId,
                )
                updateState {
                    it.copy(
                        session = result.session,
                        notice = result.notice.ifBlank {
                            "「${action.title}」已让一名临时角色加入场景。"
                        },
                    )
                }
                return@runTool
            }
            val result = plugins.invokePluginChatAction(
                runId = runId,
                sessionId = sessionId,
                pluginId = action.pluginId,
                actionId = action.actionId,
                seedText = seedText,
                selection = selection,
            )
            updateState {
                val refreshedSession = result.session.takeIf { it.sessionId.isNotBlank() }
                val suggestionOptions = result.suggestions
                    .filter { option -> option.suggestion.isNotBlank() }
                    .map { option ->
                        ChatToolOption(
                            label = option.label.ifBlank { "候选回复" },
                            value = option.suggestion,
                            description = option.suggestion,
                            messageKind = it.messageKind,
                        )
                    }
                val choiceOptions = result.choices
                    .filter { option -> option.value.isNotBlank() }
                    .map { option ->
                        ChatToolOption(
                            label = option.label.ifBlank { option.value },
                            value = option.value,
                            description = option.description,
                            messageKind = it.messageKind,
                            pluginId = action.pluginId,
                            pluginActionId = action.actionId,
                            pluginSelection = option.value,
                            pluginSeedText = seedText,
                            pluginTitle = action.title,
                        )
                    }
                if (choiceOptions.isNotEmpty()) {
                    it.copy(
                        toolOptionsTitle = result.choicePrompt.ifBlank { action.title },
                        toolOptions = choiceOptions,
                        notice = result.notice,
                    )
                } else if (refreshedSession != null) {
                    it.copy(
                        session = refreshedSession,
                        draft = result.suggestion.ifBlank { it.draft },
                        draftSpeakerOverride = if (result.character.isNotBlank()) {
                            result.character
                        } else {
                            it.draftSpeakerOverride
                        },
                        messageKind = if (result.character.isNotBlank()) "dialogue" else it.messageKind,
                        notice = result.notice.ifBlank {
                            if (result.character.isNotBlank()) {
                                "已生成「${result.character}」的回复，将以该人物身份发送。"
                            } else {
                                "「${action.title}」已更新当前会话。"
                            }
                        },
                    )
                } else if (suggestionOptions.isNotEmpty()) {
                    it.copy(
                        toolOptionsTitle = action.title,
                        toolOptions = suggestionOptions,
                    )
                } else if (result.suggestion.isNotBlank()) {
                    it.copy(
                        draft = result.suggestion,
                        draftSpeakerOverride = result.character,
                        messageKind = if (result.character.isNotBlank()) "dialogue" else it.messageKind,
                        notice = result.notice.ifBlank {
                            if (result.character.isNotBlank()) {
                                "已生成「${result.character}」的回复，将以该人物身份发送。"
                            } else {
                                "「${action.title}」已将结果放入输入框。"
                            }
                        },
                    )
                } else {
                    it.copy(
                        notice = result.notice.ifBlank {
                            "「${action.title}」没有返回可用结果。"
                        },
                    )
                }
            }
        }
    }

    private fun requestSuggestedReply(
        direction: String,
        resultMessageKind: String,
        seedText: String,
    ) {
        runTool("suggest") {
            val suggestion = dialogue.suggestReply(
                runId,
                sessionId,
                seedText = seedText,
                direction = direction,
            )
            updateState {
                it.copy(
                    draft = suggestion,
                    draftSpeakerOverride = "",
                    messageKind = resultMessageKind,
                    notice = "续写建议已放入输入框，可以修改后发送。",
                )
            }
        }
    }

    fun requestDirectorOptions(goal: String, action: String = "advance") {
        val normalizedGoal = goal.trim()
        if (normalizedGoal.isBlank()) {
            mutableState.update { it.copy(error = "请先写下希望剧情怎样发展。") }
            return
        }
        runTool("director") {
            val options = dialogue.dialogueDirectorOptions(
                runId,
                sessionId,
                goal = normalizedGoal,
                action = action,
            ).extractDirectorOptions()
            updateState {
                it.copy(
                    toolOptionsTitle = if (action == "fourth_wall") "第四面墙方案" else "剧情导演方案",
                    toolOptions = options,
                    notice = if (options.isEmpty()) "这次没有生成可用方案。" else "",
                )
            }
        }
    }

    fun chooseToolOption(option: ChatToolOption) {
        if (!state.value.canUseTools) return
        if (option.pluginId.isNotBlank() && option.pluginActionId.isNotBlank()) {
            mutableState.update {
                it.copy(toolOptions = emptyList(), toolOptionsTitle = "")
            }
            invokePluginAction(
                action = ChatPluginAction(
                    pluginId = option.pluginId,
                    pluginName = "",
                    actionId = option.pluginActionId,
                    title = option.pluginTitle,
                ),
                selection = option.pluginSelection,
                seedText = option.pluginSeedText,
            )
            return
        }
        if (option.suggestionDirection.isNotBlank()) {
            mutableState.update {
                it.copy(
                    toolOptions = emptyList(),
                    toolOptionsTitle = "",
                    messageKind = option.messageKind,
                )
            }
            requestSuggestedReply(
                direction = option.suggestionDirection,
                resultMessageKind = option.messageKind,
                seedText = "",
            )
            return
        }
        mutableState.update {
            it.copy(
                draft = option.value,
                draftSpeakerOverride = "",
                messageKind = option.messageKind,
                toolOptions = emptyList(),
                toolOptionsTitle = "",
                notice = "方案已放入输入框。",
            )
        }
    }

    fun dismissToolOptions() {
        mutableState.update { it.copy(toolOptions = emptyList(), toolOptionsTitle = "") }
    }

    fun correctLatest() {
        runTool("correct") {
            acceptSession(
                branchMutation(originKind = "consistency_correction") {
                    dialogue.correctLatestReply(runId, sessionId)
                },
                "已创建修正版分支，原会话仍然保留。",
                navigateToSession = true,
            )
        }
    }

    fun deepReviewLatest() {
        runTool("review") {
            acceptSession(
                sessionMutation { dialogue.deepReviewLatestReply(runId, sessionId) },
                "已完成最新一轮的深度复核。",
            )
        }
    }

    fun branchFromTurn(turnId: String) {
        if (turnId.isBlank()) return
        runTool("branch") {
            acceptSession(
                branchMutation(originKind = "event_timeline", originValue = turnId) {
                    dialogue.branchDialogueTurn(runId, sessionId, turnId)
                },
                "已从所选轮次创建新分支。",
                navigateToSession = true,
            )
        }
    }

    fun branchFromScene(sceneIndex: Int) {
        runTool("branch") {
            acceptSession(
                branchMutation(originKind = "scene_timeline", originValue = sceneIndex.toString()) {
                    dialogue.branchDialogueScene(runId, sessionId, sceneIndex)
                },
                "已从所选场景创建新分支。",
                navigateToSession = true,
            )
        }
    }

    fun updateBranchMeta(label: String, isMainline: Boolean) {
        runTool("branch_meta") {
            acceptSession(
                sessionMutation {
                    dialogue.updateDialogueBranchMeta(
                        runId,
                        sessionId,
                        label.trim(),
                        isMainline,
                    )
                },
                "分支信息已更新。",
            )
        }
    }

    fun setMainlineEventLocked(turnId: String, locked: Boolean) {
        val normalizedTurnId = turnId.trim()
        if (normalizedTurnId.isBlank()) return
        runTool("branch_event_lock") {
            val currentIds = snapshot.session?.branchMeta
                ?.stringList("locked_event_ids")
                .orEmpty()
            val nextIds = if (locked) {
                (currentIds + normalizedTurnId).distinct()
            } else {
                currentIds.filterNot { it == normalizedTurnId }
            }
            acceptSession(
                sessionMutation {
                    dialogue.updateDialogueBranchMeta(
                        runId = runId,
                        sessionId = sessionId,
                        lockedEventIds = nextIds,
                    )
                },
                if (locked) "已锁定为主线事件。" else "已解除主线事件锁定。",
            )
        }
    }

    fun recommendNextScene() {
        runTool("recommend_scene") {
            val payload = dialogue.recommendDialogueScene(runId, sessionId)
            val cardId = payload.stringValue("recommended_card_id")
            val transition = payload.stringValue("recommended_transition_message")
            val cards = if (snapshot.sceneCards.isEmpty()) {
                cards.listReusableCards(ReusableCardKind.Scene)
            } else {
                snapshot.sceneCards
            }
            updateState {
                it.copy(
                    sceneCards = cards,
                    recommendedSceneCardId = cardId,
                    recommendedTransition = transition,
                    notice = if (cardId.isBlank()) "目前没有可推荐的下一幕。" else "已找到一张适合承接的场景卡。",
                )
            }
        }
    }

    fun switchScene(cardId: String, transition: String = "", autoContinue: Boolean = false) {
        if (cardId.isBlank()) return
        runTool("switch_scene") {
            acceptSession(
                sessionMutation {
                    dialogue.switchDialogueScene(
                        runId,
                        sessionId,
                        cardId,
                        transition,
                        autoContinue,
                    )
                },
                "场景已切换。",
                clearSceneRecommendation = true,
            )
        }
    }

    fun saveMemory(memory: DialogueMemoryDto) {
        if (memory.text.isBlank()) {
            mutableState.update { it.copy(error = "记忆内容不能为空。") }
            return
        }
        runTool("memory") {
            val session = sessionMutation { dialogue.saveDialogueMemory(runId, sessionId, memory) }
            acceptSession(
                session,
                "会话记忆已保存。",
                memorySaved = true,
            )
            refreshMemoryQuality()
        }
    }

    fun deleteMemory(memoryId: String) {
        if (memoryId.isBlank()) return
        runTool("memory") {
            val session = sessionMutation { dialogue.deleteDialogueMemory(runId, sessionId, memoryId) }
            acceptSession(
                session,
                "会话记忆已删除。",
            )
            refreshMemoryQuality()
        }
    }

    fun updateAutomaticMemoryStatus(memoryId: String, status: String) {
        if (memoryId.isBlank()) return
        runTool("memory_quality") {
            val report = dialogue.updateAutomaticMemoryStatus(runId, sessionId, memoryId, status)
            updateState {
                it.copy(
                    memoryQuality = report,
                    notice = when (status) {
                        "stale" -> "已将自动记忆标记为过期。"
                        "conflict" -> "已将自动记忆标记为冲突。"
                        else -> "自动记忆已恢复使用。"
                    },
                )
            }
        }
    }

    fun mergeDuplicateMemories() {
        runTool("memory_quality") {
            val before = snapshot.memoryQuality.duplicateGroups.size
            val report = dialogue.mergeDuplicateDialogueMemories(runId, sessionId)
            updateState {
                it.copy(
                    memoryQuality = report,
                    notice = if (before > 0) "重复自动记忆已合并。" else "没有发现可合并的重复记忆。",
                )
            }
        }
    }

    private suspend fun ToolRequest.refreshMemoryQuality() {
        val report = dialogue.getDialogueMemoryQuality(runId, sessionId)
        updateState { it.copy(memoryQuality = report) }
    }

    fun setRelationLock(pairKey: String, locked: Boolean) {
        if (pairKey.isBlank()) return
        runTool("relation_lock") {
            acceptSession(
                sessionMutation {
                    dialogue.setDialogueRelationLock(
                        runId,
                        sessionId,
                        pairKey,
                        locked,
                    )
                },
                if (locked) "已锁定这组关系。" else "已解除关系锁定。",
            )
        }
    }

    fun clearNotice() {
        mutableState.update { it.copy(notice = "") }
    }

    fun clearError() {
        mutableState.update { it.copy(error = "") }
    }

    private fun runTool(name: String, block: suspend ToolRequest.() -> Unit) {
        val snapshot = state.value
        if (!snapshot.canUseTools) return

        val request = ToolRequest(++nextToolRequestId, snapshot)
        activeToolRequest = request
        mutableState.update {
            if (
                it.runId == request.runId &&
                it.sessionId == request.sessionId &&
                it.canUseTools
            ) {
                it.copy(toolBusy = name, error = "", notice = "")
            } else {
                it
            }
        }
        toolJob = viewModelScope.launch {
            try {
                request.block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                request.updateState {
                    it.copy(error = error.readableMessage("操作失败，请稍后重试。"))
                }
            } finally {
                if (activeToolRequest?.id == request.id) {
                    activeToolRequest = null
                    mutableState.update { it.copy(toolBusy = "") }
                    toolJob = null
                }
            }
        }
    }

    private fun ToolRequest.acceptSession(
        session: DialogueSessionDto,
        notice: String,
        navigateToSession: Boolean = false,
        memorySaved: Boolean = false,
        clearSceneRecommendation: Boolean = false,
    ) {
        updateState {
            it.copy(
                runId = session.runId.ifBlank { it.runId },
                sessionId = session.sessionId.ifBlank { it.sessionId },
                session = session,
                sendOutcomeUnknown = false,
                sendBaselineTranscript = null,
                navigationSession = if (navigateToSession) session else it.navigationSession,
                memorySaveRevision = if (memorySaved) {
                    it.memorySaveRevision + 1
                } else {
                    it.memorySaveRevision
                },
                recommendedSceneCardId = if (clearSceneRecommendation) {
                    ""
                } else {
                    it.recommendedSceneCardId
                },
                recommendedTransition = if (clearSceneRecommendation) {
                    ""
                } else {
                    it.recommendedTransition
                },
                notice = notice,
                error = "",
            )
        }
    }

    private suspend fun ToolRequest.sessionMutation(
        operation: suspend () -> DialogueSessionDto,
    ): DialogueSessionDto = toolsDelegate.sessionMutation(
        runId = runId,
        sessionId = sessionId,
        previousSession = snapshot.session,
        operation = operation,
    )

    private suspend fun ToolRequest.branchMutation(
        originKind: String,
        originValue: String = "",
        operation: suspend () -> DialogueSessionDto,
    ): DialogueSessionDto {
        return toolsDelegate.branchMutation(
            runId = runId,
            sessionId = sessionId,
            originKind = originKind,
            originValue = originValue,
            operation = operation,
        )
    }

    fun consumeNavigationSession() {
        mutableState.update { it.copy(navigationSession = null) }
    }

    private fun ToolRequest.updateState(transform: (ChatUiState) -> ChatUiState) {
        mutableState.update { current ->
            if (
                activeToolRequest?.id == id &&
                current.runId == runId &&
                current.sessionId == sessionId
            ) {
                transform(current)
            } else {
                current
            }
        }
    }

    private fun updateLoadState(
        requestId: Long,
        runId: String,
        sessionId: String,
        transform: (ChatUiState) -> ChatUiState,
    ) {
        mutableState.update { current ->
            if (
                loadRequestId == requestId &&
                current.runId == runId &&
                current.sessionId == sessionId
            ) {
                transform(current)
            } else {
                current
            }
        }
    }

    private fun cancelLoadRequest() {
        loadRequestId += 1
        loadJob?.cancel()
        loadJob = null
    }

    private fun cancelToolRequest() {
        activeToolRequest = null
        toolJob?.cancel()
        toolJob = null
        mutableState.update {
            if (it.toolBusy.isBlank()) it else it.copy(toolBusy = "")
        }
    }

    private fun cancelRecoveryRequest() {
        nextRecoveryRequestId += 1
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private fun updateRecoveryState(
        requestId: Long,
        snapshot: ChatUiState,
        transform: (ChatUiState) -> ChatUiState,
    ) {
        mutableState.update { current ->
            if (
                nextRecoveryRequestId == requestId &&
                current.runId == snapshot.runId &&
                current.sessionId == snapshot.sessionId
            ) {
                transform(current)
            } else {
                current
            }
        }
    }

    private fun updateSendState(
        snapshot: ChatUiState,
        operationId: String,
        transform: (ChatUiState) -> ChatUiState,
    ) {
        mutableState.update { current ->
            if (
                current.runId == snapshot.runId &&
                current.sessionId == snapshot.sessionId &&
                current.failedOperationId == operationId
            ) {
                transform(current)
            } else {
                current
            }
        }
    }

    private companion object {
        val messageKinds = setOf("dialogue", "narration", "plot", "fourth_wall")
        val pacingOptions = setOf("brief", "normal", "detailed")
        const val CONTINUOUS_OBSERVE_DELAY_MS = 480L
        const val INITIAL_TRANSCRIPT_PAGE = 100
        const val EARLIER_TRANSCRIPT_PAGE = 100
    }
}

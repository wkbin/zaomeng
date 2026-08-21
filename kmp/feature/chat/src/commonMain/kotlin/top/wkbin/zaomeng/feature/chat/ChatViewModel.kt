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
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()
    private val toolsDelegate = ChatToolsDelegate(
        dialogue = dialogue,
        sessions = sessions,
        plugins = plugins,
        cardsRepository = cards,
        scope = viewModelScope,
        currentState = { state.value },
        updateState = { transform -> mutableState.update(transform) },
    )
    private val streamEngine = ChatStreamEngine(
        dialogue = dialogue,
        sessions = sessions,
        scope = viewModelScope,
        currentState = { state.value },
        updateState = { transform -> mutableState.update(transform) },
    )
    private val continuousObserveController = ContinuousObserveController(
        scope = viewModelScope,
        streamEngine = streamEngine,
        currentState = { state.value },
        updateState = { transform -> mutableState.update(transform) },
    )

    private var loadJob: Job? = null
    private var pluginActionsJob: Job? = null
    private var loadRequestId = 0L
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
            streamEngine.cancel()
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
            streamEngine.cancel()
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
        toolsDelegate.toggleGenerationEnhancer(enhancer)
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

        streamEngine.start(
            snapshot = snapshot,
            message = message,
            messageKind = snapshot.messageKind,
            speakerOverride = snapshot.draftSpeakerOverride,
            operationId = clientRandomUuid(),
        )
    }

    fun toggleContinuousObserve() = continuousObserveController.toggle()

    fun pauseContinuousObserve() = continuousObserveController.pause()

    private fun stopContinuousObserve(notice: String) =
        continuousObserveController.stop(notice)

    fun retryLastSend() {
        val snapshot = state.value
        val pending = snapshot.pendingUserMessage
        if (
            snapshot.failedOperationId.isBlank() || snapshot.failedMessage.isBlank() ||
            snapshot.sending || snapshot.recovering || snapshot.sendOutcomeUnknown ||
            pending?.status != PendingUserMessageStatus.Failed || !pending.retryable
        ) return
        streamEngine.start(
            snapshot = snapshot,
            message = snapshot.failedMessage,
            messageKind = snapshot.failedMessageKind,
            speakerOverride = snapshot.failedSpeakerOverride,
            operationId = snapshot.failedOperationId,
        )
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
                    streamEngine.resolveCommittedAppend(
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

    fun loadSceneCards() = toolsDelegate.loadSceneCards()

    fun suggestReply(direction: String = "") = toolsDelegate.suggestReply(direction)

    fun invokePluginAction(action: ChatPluginAction) = toolsDelegate.invokePluginAction(action)

    fun requestDirectorOptions(goal: String, action: String = "advance") =
        toolsDelegate.requestDirectorOptions(goal, action)

    fun chooseToolOption(option: ChatToolOption) = toolsDelegate.chooseToolOption(option)

    fun dismissToolOptions() = toolsDelegate.dismissToolOptions()

    fun correctLatest() = toolsDelegate.correctLatest()

    fun deepReviewLatest() = toolsDelegate.deepReviewLatest()

    fun branchFromTurn(turnId: String) = toolsDelegate.branchFromTurn(turnId)

    fun branchFromScene(sceneIndex: Int) = toolsDelegate.branchFromScene(sceneIndex)

    fun updateBranchMeta(label: String, isMainline: Boolean) =
        toolsDelegate.updateBranchMeta(label, isMainline)

    fun setMainlineEventLocked(turnId: String, locked: Boolean) =
        toolsDelegate.setMainlineEventLocked(turnId, locked)

    fun recommendNextScene() = toolsDelegate.recommendNextScene()

    fun switchScene(cardId: String, transition: String = "", autoContinue: Boolean = false) =
        toolsDelegate.switchScene(cardId, transition, autoContinue)

    fun saveMemory(memory: DialogueMemoryDto) = toolsDelegate.saveMemory(memory)

    fun deleteMemory(memoryId: String) = toolsDelegate.deleteMemory(memoryId)

    fun updateAutomaticMemoryStatus(memoryId: String, status: String) =
        toolsDelegate.updateAutomaticMemoryStatus(memoryId, status)

    fun mergeDuplicateMemories() = toolsDelegate.mergeDuplicateMemories()

    fun setRelationLock(pairKey: String, locked: Boolean) =
        toolsDelegate.setRelationLock(pairKey, locked)

    fun clearNotice() {
        mutableState.update { it.copy(notice = "") }
    }

    fun clearError() {
        mutableState.update { it.copy(error = "") }
    }

    fun consumeNavigationSession() {
        mutableState.update { it.copy(navigationSession = null) }
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
        toolsDelegate.cancel()
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

    private companion object {
        val messageKinds = setOf("dialogue", "narration", "plot", "fourth_wall")
        val pacingOptions = setOf("brief", "normal", "detailed")
        const val INITIAL_TRANSCRIPT_PAGE = 100
        const val EARLIER_TRANSCRIPT_PAGE = 100
    }
}

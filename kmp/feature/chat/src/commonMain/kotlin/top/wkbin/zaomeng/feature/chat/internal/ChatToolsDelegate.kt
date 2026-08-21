package top.wkbin.zaomeng.feature.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.CardRepository
import top.wkbin.zaomeng.data.DialogueRepository
import top.wkbin.zaomeng.data.PluginRepository
import top.wkbin.zaomeng.data.ReusableCardKind
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.api.DialogueMemoryDto
import top.wkbin.zaomeng.data.api.DialogueSessionDto

/** Owns Chat tool execution, stale-request guards, and recovery-aware mutations. */
internal class ChatToolsDelegate(
    private val dialogue: DialogueRepository,
    private val sessions: SessionRepository,
    private val plugins: PluginRepository,
    private val cardsRepository: CardRepository,
    private val scope: CoroutineScope,
    private val currentState: () -> ChatUiState,
    private val updateState: (((ChatUiState) -> ChatUiState) -> Unit),
) {
    private var toolJob: Job? = null
    private var nextRequestId = 0L
    private var activeRequest: ToolRequest? = null

    private data class ToolRequest(
        val id: Long,
        val snapshot: ChatUiState,
    ) {
        val runId: String get() = snapshot.runId
        val sessionId: String get() = snapshot.sessionId
    }

    fun cancel() {
        activeRequest = null
        toolJob?.cancel()
        toolJob = null
        updateState { if (it.toolBusy.isBlank()) it else it.copy(toolBusy = "") }
    }

    fun loadSceneCards() {
        val state = currentState()
        if (state.sceneCards.isNotEmpty() || state.toolBusy.isNotBlank()) return
        runTool("scene_cards") {
            val cards = cardsRepository.listReusableCards(ReusableCardKind.Scene)
            updateState { it.copy(sceneCards = cards) }
        }
    }

    fun toggleGenerationEnhancer(enhancer: ChatGenerationEnhancer) {
        val current = currentState()
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
                    ) {
                        enabled
                    } else {
                        it.includeInnerThoughts
                    },
                    notice = "「${enhancer.title}」已${if (enabled) "开启" else "关闭"}，仅对当前聊天生效。",
                )
            }
        }
    }

    fun suggestReply(direction: String = "") {
        val current = currentState()
        requestSuggestedReply(direction, current.messageKind, current.draft)
    }

    fun invokePluginAction(action: ChatPluginAction) {
        val current = currentState()
        invokePluginAction(action, selection = "", seedText = current.draft)
    }

    fun requestDirectorOptions(goal: String, action: String = "advance") {
        val normalizedGoal = goal.trim()
        if (normalizedGoal.isBlank()) {
            updateState { it.copy(error = "请先写下希望剧情怎样发展。") }
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
        if (!currentState().canUseTools) return
        if (option.pluginId.isNotBlank() && option.pluginActionId.isNotBlank()) {
            updateState { it.copy(toolOptions = emptyList(), toolOptionsTitle = "") }
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
            updateState {
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
        updateState {
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
        updateState { it.copy(toolOptions = emptyList(), toolOptionsTitle = "") }
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
                    dialogue.updateDialogueBranchMeta(runId, sessionId, label.trim(), isMainline)
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
                cardsRepository.listReusableCards(ReusableCardKind.Scene)
            } else {
                snapshot.sceneCards
            }
            updateState {
                it.copy(
                    sceneCards = cards,
                    recommendedSceneCardId = cardId,
                    recommendedTransition = transition,
                    notice = if (cardId.isBlank()) {
                        "目前没有可推荐的下一幕。"
                    } else {
                        "已找到一张适合承接的场景卡。"
                    },
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
            updateState { it.copy(error = "记忆内容不能为空。") }
            return
        }
        runTool("memory") {
            val session = sessionMutation {
                dialogue.saveDialogueMemory(runId, sessionId, memory)
            }
            acceptSession(session, "会话记忆已保存。", memorySaved = true)
            refreshMemoryQuality()
        }
    }

    fun deleteMemory(memoryId: String) {
        if (memoryId.isBlank()) return
        runTool("memory") {
            val session = sessionMutation {
                dialogue.deleteDialogueMemory(runId, sessionId, memoryId)
            }
            acceptSession(session, "会话记忆已删除。")
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
                    notice = if (before > 0) {
                        "重复自动记忆已合并。"
                    } else {
                        "没有发现可合并的重复记忆。"
                    },
                )
            }
        }
    }

    fun setRelationLock(pairKey: String, locked: Boolean) {
        if (pairKey.isBlank()) return
        runTool("relation_lock") {
            acceptSession(
                sessionMutation {
                    dialogue.setDialogueRelationLock(runId, sessionId, pairKey, locked)
                },
                if (locked) "已锁定这组关系。" else "已解除关系锁定。",
            )
        }
    }

    private fun invokePluginAction(
        action: ChatPluginAction,
        selection: String,
        seedText: String,
    ) {
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
                val refreshedSession = result.session.takeIf { session -> session.sessionId.isNotBlank() }
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
                when {
                    choiceOptions.isNotEmpty() -> it.copy(
                        toolOptionsTitle = result.choicePrompt.ifBlank { action.title },
                        toolOptions = choiceOptions,
                        notice = result.notice,
                    )
                    refreshedSession != null -> it.copy(
                        session = refreshedSession,
                        draft = result.suggestion.ifBlank { it.draft },
                        draftSpeakerOverride = result.character.ifBlank {
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
                    suggestionOptions.isNotEmpty() -> it.copy(
                        toolOptionsTitle = action.title,
                        toolOptions = suggestionOptions,
                    )
                    result.suggestion.isNotBlank() -> it.copy(
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
                    else -> it.copy(
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

    private fun runTool(name: String, block: suspend ToolRequest.() -> Unit) {
        val snapshot = currentState()
        if (!snapshot.canUseTools) return

        val request = ToolRequest(++nextRequestId, snapshot)
        activeRequest = request
        updateState {
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
        toolJob = scope.launch {
            try {
                request.block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                request.updateState {
                    it.copy(error = error.readableMessage("操作失败，请稍后重试。"))
                }
            } finally {
                if (activeRequest?.id == request.id) {
                    activeRequest = null
                    updateState { it.copy(toolBusy = "") }
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
    ): DialogueSessionDto = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        val recovered = try {
            sessions.getSession(runId, sessionId, includeTranscript = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        recovered?.takeIf { it != snapshot.session } ?: throw error
    }

    private suspend fun ToolRequest.branchMutation(
        originKind: String,
        originValue: String = "",
        operation: suspend () -> DialogueSessionDto,
    ): DialogueSessionDto {
        val knownSessionIds = try {
            sessions.listSessions(runId).mapTo(mutableSetOf()) { it.sessionId }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        return try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (knownSessionIds == null) throw error
            val recovered = try {
                sessions.listSessions(runId)
                    .asSequence()
                    .filter { it.sessionId !in knownSessionIds }
                    .filter { it.branchOrigin.stringValue("session_id") == sessionId }
                    .filter { it.branchOrigin.stringValue("kind") == originKind }
                    .filter {
                        when (originKind) {
                            "event_timeline", "consistency_correction" ->
                                originValue.isBlank() ||
                                    it.branchOrigin.stringValue("turn_id") == originValue
                            "scene_timeline" ->
                                it.branchOrigin.stringValue("scene_index") == originValue
                            else -> true
                        }
                    }
                    .singleOrNull()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            recovered ?: throw error
        }
    }

    private suspend fun ToolRequest.refreshMemoryQuality() {
        val report = dialogue.getDialogueMemoryQuality(runId, sessionId)
        updateState { it.copy(memoryQuality = report) }
    }

    private fun ToolRequest.updateState(transform: (ChatUiState) -> ChatUiState) {
        updateState { current ->
            if (activeRequest?.id == id && current.matchesSession(runId, sessionId)) {
                transform(current)
            } else {
                current
            }
        }
    }
}

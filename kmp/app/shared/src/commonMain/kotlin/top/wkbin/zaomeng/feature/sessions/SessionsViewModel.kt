package top.wkbin.zaomeng.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.ReusableCardKind
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.SessionRefDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class NewSessionDraft(
    val runId: String = "",
    val mode: String = "observe",
    val participants: Set<String> = emptySet(),
    val controlledCharacter: String = "",
    val selfName: String = "",
    val selfIdentity: String = "",
    val selfStyle: String = "",
    val sceneCardId: String = "",
    val selfCardId: String = "",
    val openingPresetId: String = "",
    val sceneProfile: JsonObject = JsonObject(emptyMap()),
    val selfProfile: JsonObject = JsonObject(emptyMap()),
)

enum class SessionsSort(val label: String) {
    Recent("最近活跃"),
    Title("按书名"),
}

data class SessionsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val creating: Boolean = false,
    val avatarBytes: Map<String, ByteArray> = emptyMap(),
    /** 当前过滤/排序条件下会话总数（首屏分页返回后填充，用于列表计数展示）。 */
    val totalSessions: Int? = null,
    val runs: List<RunManifestDto> = emptyList(),
    val searchQuery: String = "",
    val sort: SessionsSort = SessionsSort.Recent,
    val sceneCards: List<ReusableCardDto> = emptyList(),
    val selfCards: List<ReusableCardDto> = emptyList(),
    val openingPresets: List<ReusableCardDto> = emptyList(),
    val recommendingScene: Boolean = false,
    val scopedRunId: String? = null,
    val createDialogVisible: Boolean = false,
    val draft: NewSessionDraft = NewSessionDraft(),
    val selectionMode: Boolean = false,
    val selectedSessionKeys: Set<String> = emptySet(),
    val deletingSelection: Boolean = false,
    val deletingSessionKeys: Set<String> = emptySet(),
    val createdSession: DialogueSessionDto? = null,
    val error: String = "",
) {
    val selectedRun: RunManifestDto?
        get() = runs.firstOrNull { it.runId == draft.runId }

    val availableCharacters: List<String>
        get() = selectedRun?.availableCharacters.orEmpty()

    val canCreate: Boolean
        get() = draft.runId.isNotBlank() &&
            draft.participants.isNotEmpty() &&
            when (draft.mode) {
                "act" -> draft.controlledCharacter in draft.participants
                "insert" -> draft.selfName.isNotBlank() && draft.selfIdentity.isNotBlank()
                else -> true
            }
}

class SessionsViewModel(
    private val repository: ZaomengRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = mutableState.asStateFlow()

    /**
     * 会话列表分页流。runId / 搜索词 / 排序变化时重建 Pager（查询身份变化），
     * 其余刷新走 PagingSource.invalidate()，保留已加载缓存与滚动位置。
     */
    private val pagerFlow = MutableStateFlow<Pager<Int, DialogueSessionDto>?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val sessions: Flow<PagingData<DialogueSessionDto>> = pagerFlow
        .filterNotNull()
        .flatMapLatest { pager -> pager.flow.cachedIn(viewModelScope) }

    private var pagerParams: PagerParams? = null
    private var currentSource: SessionsPagingSource? = null
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var avatarJob: Job? = null
    private var hasResumed = false
    private val loadedAvatarKeys = mutableSetOf<String>()

    fun load(runId: String? = null, force: Boolean = false) {
        val normalizedRunId = runId?.trim()?.takeIf(String::isNotEmpty)
        val current = state.value
        if (!force && !current.loading && current.scopedRunId == normalizedRunId) return

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loading = it.runs.isEmpty(),
                    refreshing = it.runs.isNotEmpty(),
                    scopedRunId = normalizedRunId,
                    error = "",
                )
            }
            try {
                val loaded = coroutineScope {
                    val runsRequest = async { repository.listRuns() }
                    val scenesRequest = async { loadOptionalCards(ReusableCardKind.Scene) }
                    val selvesRequest = async { loadOptionalCards(ReusableCardKind.Self) }
                    val presetsRequest = async { loadOptionalCards(ReusableCardKind.Opening) }
                    LoadedSessionResources(
                        runs = runsRequest.await(),
                        sceneCards = scenesRequest.await(),
                        selfCards = selvesRequest.await(),
                        openingPresets = presetsRequest.await(),
                    )
                }
                mutableState.update { old ->
                    val selectedRunId = normalizedRunId?.let { scopedId ->
                        loaded.runs.firstOrNull {
                            it.runId == scopedId && it.availableCharacters.isNotEmpty()
                        }?.runId.orEmpty()
                    } ?: selectInitialRunId(
                        runs = loaded.runs,
                        preferredRunId = old.draft.runId,
                    )
                    old.copy(
                        loading = false,
                        refreshing = false,
                        runs = loaded.runs,
                        sceneCards = loaded.sceneCards,
                        selfCards = loaded.selfCards,
                        openingPresets = loaded.openingPresets,
                        selectionMode = false,
                        selectedSessionKeys = emptySet(),
                        draft = if (old.createDialogVisible) {
                            old.draft
                        } else {
                            newDraftForRun(loaded.runs, selectedRunId)
                        },
                        error = "",
                    )
                }
                rebuildPagerIfNeeded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = error.readableMessage("会话加载失败，请稍后重试。"),
                    )
                }
            }
        }
    }

    fun refresh() {
        val current = state.value
        if (current.creating || current.deletingSessionKeys.isNotEmpty()) return
        load(state.value.scopedRunId, force = true)
    }

    fun renameSession(session: DialogueSessionDto, title: String) {
        val normalizedTitle = title.trim().take(80)
        if (normalizedTitle.isBlank()) {
            mutableState.update { it.copy(error = "会话标题不能为空。") }
            return
        }
        viewModelScope.launch {
            try {
                repository.updateSessionTitle(
                    runId = session.runId,
                    sessionId = session.sessionId,
                    title = normalizedTitle,
                )
                mutableState.update { it.copy(error = "") }
                rebuildPagerIfNeeded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(error = error.readableMessage("会话标题更新失败，请稍后重试。"))
                }
            }
        }
    }

    fun updateSearchQuery(value: String) {
        mutableState.update { it.copy(searchQuery = value.take(120)) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            rebuildPagerIfNeeded()
        }
    }

    fun selectSort(sort: SessionsSort) {
        mutableState.update { it.copy(sort = sort) }
        rebuildPagerIfNeeded()
    }

    fun enterSelectionMode() {
        if (state.value.deletingSessionKeys.isNotEmpty()) return
        mutableState.update { it.copy(selectionMode = true, error = "") }
    }

    fun exitSelectionMode() {
        if (state.value.deletingSelection) return
        mutableState.update {
            it.copy(
                selectionMode = false,
                selectedSessionKeys = emptySet(),
            )
        }
    }

    fun toggleSessionSelection(sessionKey: String) {
        mutableState.update { current ->
            if (!current.selectionMode || current.deletingSelection) {
                current
            } else {
                current.copy(
                    selectedSessionKeys = if (sessionKey in current.selectedSessionKeys) {
                        current.selectedSessionKeys - sessionKey
                    } else {
                        current.selectedSessionKeys + sessionKey
                    },
                )
            }
        }
    }

    /** 全选/取消全选当前已加载的可见会话（Paging 只保证已加载窗口可枚举）。 */
    fun toggleAllVisibleSessions(visibleKeys: Set<String>) {
        mutableState.update { current ->
            if (!current.selectionMode || current.deletingSelection || visibleKeys.isEmpty()) {
                current
            } else {
                current.copy(
                    selectedSessionKeys = toggleVisibleSelection(
                        selectedKeys = current.selectedSessionKeys,
                        visibleKeys = visibleKeys,
                    ),
                )
            }
        }
    }

    fun onScreenResumed() {
        if (hasResumed) {
            if (!state.value.creating && state.value.deletingSessionKeys.isEmpty()) {
                refresh()
            }
        } else {
            hasResumed = true
        }
    }

    fun openCreateDialog() {
        val current = state.value
        val selectedRunId = current.scopedRunId?.let { scopedId ->
            current.runs.firstOrNull {
                it.runId == scopedId && it.availableCharacters.isNotEmpty()
            }?.runId.orEmpty()
        } ?: selectInitialRunId(
            runs = current.runs,
            preferredRunId = current.draft.runId,
        )
        if (selectedRunId.isBlank()) {
            mutableState.update {
                it.copy(error = "还没有可聊天的人物资料，请先完成一本书的人物蒸馏。")
            }
            return
        }
        mutableState.update {
            it.copy(
                createDialogVisible = true,
                draft = newDraftForRun(it.runs, selectedRunId),
                error = "",
            )
        }
    }

    fun closeCreateDialog() {
        if (state.value.creating) return
        mutableState.update { it.copy(createDialogVisible = false) }
    }

    fun selectRun(runId: String) {
        if (state.value.creating) return
        mutableState.update { current ->
            current.copy(draft = newDraftForRun(current.runs, runId), error = "")
        }
    }

    fun selectMode(mode: String) {
        if (mode !in dialogueModes || state.value.creating) return
        mutableState.update { current ->
            val selected = current.draft.participants
            current.copy(
                draft = current.draft.copy(
                    mode = mode,
                    openingPresetId = "",
                    controlledCharacter = if (mode == "act") {
                        current.draft.controlledCharacter.takeIf { it in selected }
                            ?: selected.firstOrNull().orEmpty()
                    } else {
                        ""
                    },
                ),
                error = "",
            )
        }
    }

    fun toggleParticipant(character: String) {
        if (state.value.creating || character.isBlank()) return
        mutableState.update { current ->
            val selected = current.draft.participants.toMutableSet().apply {
                if (!add(character)) remove(character)
            }
            val controlled = if (current.draft.mode == "act") {
                current.draft.controlledCharacter
                    .takeIf { it in selected }
                    ?: selected.firstOrNull().orEmpty()
            } else {
                ""
            }
            current.copy(
                draft = current.draft.copy(
                    participants = selected,
                    controlledCharacter = controlled,
                    openingPresetId = "",
                ),
                error = "",
            )
        }
    }

    fun selectControlledCharacter(character: String) {
        if (state.value.creating || character !in state.value.draft.participants) return
        mutableState.update {
            it.copy(
                draft = it.draft.copy(controlledCharacter = character, openingPresetId = ""),
                error = "",
            )
        }
    }

    fun updateSelfName(value: String) {
        mutableState.update {
            it.copy(draft = it.draft.copy(selfName = value, openingPresetId = ""), error = "")
        }
    }

    fun updateSelfIdentity(value: String) {
        mutableState.update {
            it.copy(draft = it.draft.copy(selfIdentity = value, openingPresetId = ""), error = "")
        }
    }

    fun updateSelfStyle(value: String) {
        mutableState.update {
            it.copy(draft = it.draft.copy(selfStyle = value, openingPresetId = ""), error = "")
        }
    }

    fun selectSceneCard(cardId: String) {
        if (state.value.creating) return
        mutableState.update { current ->
            val card = current.sceneCards.firstOrNull { it.cardId == cardId }
            current.copy(
                draft = current.draft.copy(
                    sceneCardId = card?.cardId.orEmpty(),
                    sceneProfile = card?.fields ?: JsonObject(emptyMap()),
                    openingPresetId = "",
                ),
                error = "",
            )
        }
    }

    fun recommendSceneCard() {
        val snapshot = state.value
        if (snapshot.recommendingScene || snapshot.draft.participants.isEmpty()) return
        val requestContext = snapshot.draft.sceneRecommendationContext()
        viewModelScope.launch {
            mutableState.update { it.copy(recommendingScene = true, error = "") }
            try {
                val cardId = repository.recommendSceneCard(
                    snapshot.draft.mode,
                    snapshot.draft.participants.toList(),
                )
                mutableState.update { current ->
                    val card = current.sceneCards.firstOrNull { it.cardId == cardId }
                    val requestStillCurrent = current.createDialogVisible &&
                        current.draft.sceneRecommendationContext() == requestContext
                    current.copy(
                        recommendingScene = false,
                        draft = if (card == null || !requestStillCurrent) current.draft else current.draft.copy(
                            sceneCardId = card.cardId,
                            sceneProfile = card.fields,
                            openingPresetId = "",
                        ),
                        error = if (card == null && requestStillCurrent) {
                            "当前没有合适的场景卡，请先在资料库创建一张。"
                        } else {
                            ""
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(recommendingScene = false, error = error.readableMessage("场景推荐失败。"))
                }
            }
        }
    }

    fun selectSelfCard(cardId: String) {
        if (state.value.creating) return
        mutableState.update { current ->
            val card = current.selfCards.firstOrNull { it.cardId == cardId }
            val fields = card?.fields ?: JsonObject(emptyMap())
            current.copy(
                draft = current.draft.copy(
                    selfCardId = card?.cardId.orEmpty(),
                    selfProfile = fields,
                    selfName = fields.stringValue("display_name"),
                    selfIdentity = fields.stringValue("scene_identity")
                        .ifBlank { fields.stringValue("core_identity") },
                    selfStyle = fields.stringValue("interaction_style"),
                    openingPresetId = "",
                ),
                error = "",
            )
        }
    }

    fun selectOpeningPreset(cardId: String) {
        if (state.value.creating) return
        mutableState.update { current ->
            val preset = current.openingPresets.firstOrNull { it.cardId == cardId }
                ?: return@update current.copy(
                    draft = current.draft.copy(openingPresetId = ""),
                    error = "",
                )
            val fields = preset.fields
            val mode = fields.stringValue("mode").takeIf { it in dialogueModes } ?: "observe"
            val available = current.availableCharacters.toSet()
            val participants = fields["participants"]
                ?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { it in available }
                ?.toSet()
                .orEmpty()
                .ifEmpty { current.draft.participants }
            val sceneCardId = fields.stringValue("scene_card_id")
            val selfCardId = fields.stringValue("self_card_id")
            val sceneProfile = fields["scene_card"]
                ?.let { runCatching { it.jsonObject["fields"]?.jsonObject ?: it.jsonObject }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: current.sceneCards.firstOrNull { it.cardId == sceneCardId }?.fields
                ?: JsonObject(emptyMap())
            val selfProfile = fields["self_card"]
                ?.let { runCatching { it.jsonObject["fields"]?.jsonObject ?: it.jsonObject }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: current.selfCards.firstOrNull { it.cardId == selfCardId }?.fields
                ?: JsonObject(emptyMap())
            val controlled = fields.stringValue("controlled_character")
                .takeIf { it in participants }
                ?: participants.firstOrNull().orEmpty()
            val selfName = fields.stringValue("self_name")
                .ifBlank { selfProfile.stringValue("display_name") }
            val selfIdentity = fields.stringValue("self_identity")
                .ifBlank { selfProfile.stringValue("scene_identity") }
                .ifBlank { selfProfile.stringValue("core_identity") }
            val selfStyle = fields.stringValue("self_style")
                .ifBlank { selfProfile.stringValue("interaction_style") }
            current.copy(
                draft = current.draft.copy(
                    openingPresetId = preset.cardId,
                    mode = mode,
                    participants = participants,
                    controlledCharacter = if (mode == "act") controlled else "",
                    sceneCardId = sceneCardId,
                    sceneProfile = sceneProfile,
                    selfCardId = selfCardId,
                    selfProfile = selfProfile,
                    selfName = selfName,
                    selfIdentity = selfIdentity,
                    selfStyle = selfStyle,
                ),
                error = "",
            )
        }
    }

    fun createSession() {
        val snapshot = state.value
        if (snapshot.creating) return
        if (!snapshot.canCreate) {
            mutableState.update {
                it.copy(error = createValidationMessage(it.draft))
            }
            return
        }

        val draft = snapshot.draft
        loadJob?.cancel()
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    creating = true,
                    error = "",
                )
            }
            val knownSessionKeys = try {
                repository.listSessions(draft.runId).mapTo(mutableSetOf(), DialogueSessionDto::key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptySet()
            }
            try {
                val created = repository.createSession(
                    runId = draft.runId,
                    mode = draft.mode,
                    participants = draft.participants.toList(),
                    controlledCharacter = draft.controlledCharacter.takeIf { draft.mode == "act" }.orEmpty(),
                    selfName = draft.selfName.trim(),
                    selfIdentity = draft.selfIdentity.trim(),
                    selfStyle = draft.selfStyle.trim(),
                    sceneCardId = draft.sceneCardId,
                    sceneProfile = draft.sceneProfile,
                    selfCardId = draft.selfCardId,
                    selfCardProfile = draft.selfProfile,
                )
                mutableState.update { current ->
                    current.afterSessionCreated(created)
                }
                ensureAvatars(listOf(created))
                rebuildPagerIfNeeded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val recovered = try {
                    repository.listSessions(draft.runId).firstOrNull { session ->
                        session.key !in knownSessionKeys && session.matches(draft)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                mutableState.update { current ->
                    recovered?.let { current.afterSessionCreated(it) } ?: current.copy(
                        creating = false,
                        error = error.readableMessage("创建会话失败，请检查模型配置后重试。"),
                    )
                }
                if (recovered != null) rebuildPagerIfNeeded()
            }
        }
    }

    fun consumeCreatedSession() {
        mutableState.update { it.copy(createdSession = null) }
    }

    fun deleteSession(session: DialogueSessionDto) {
        val key = session.key
        if (key in state.value.deletingSessionKeys) return
        loadJob?.cancel()
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    deletingSessionKeys = it.deletingSessionKeys + key,
                    error = "",
                )
            }
            try {
                repository.deleteSession(session.runId, session.sessionId)
                mutableState.update {
                    it.copy(deletingSessionKeys = it.deletingSessionKeys - key)
                }
                rebuildPagerIfNeeded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val sessionStillExists = try {
                    repository.listSessions(session.runId).any { it.key == key }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                mutableState.update { current ->
                    if (sessionStillExists == false) {
                        current.copy(
                            deletingSessionKeys = current.deletingSessionKeys - key,
                            error = "",
                        )
                    } else {
                        current.copy(
                            deletingSessionKeys = current.deletingSessionKeys - key,
                            error = error.readableMessage("删除会话失败，请稍后重试。"),
                        )
                    }
                }
                if (sessionStillExists == false) rebuildPagerIfNeeded()
            }
        }
    }

    fun deleteSelectedSessions() {
        val snapshot = state.value
        if (!snapshot.selectionMode || snapshot.deletingSelection) return
        val selectedKeys = snapshot.selectedSessionKeys
        if (selectedKeys.isEmpty()) return

        loadJob?.cancel()
        mutableState.update {
            it.copy(
                loading = false,
                refreshing = false,
                deletingSelection = true,
                deletingSessionKeys = it.deletingSessionKeys + selectedKeys,
                error = "",
            )
        }
        viewModelScope.launch {
            // 已选中的会话可能位于未加载的页面之外；先从服务端取回完整列表定位 run/session 映射。
            val selectedRefs = try {
                repository.listSessions(snapshot.scopedRunId)
                    .filter { it.key in selectedKeys }
                    .map { SessionRefDto(runId = it.runId, sessionId = it.sessionId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
            if (selectedRefs.isEmpty()) {
                mutableState.update {
                    it.copy(
                        deletingSelection = false,
                        deletingSessionKeys = it.deletingSessionKeys - selectedKeys,
                        error = "批量删除失败：无法定位选中会话，请刷新后重试。",
                    )
                }
                return@launch
            }
            try {
                val result = repository.deleteSessions(selectedRefs)
                val handledKeys = handledSessionKeys(
                    selectedKeys = selectedKeys,
                    refs = result.deleted + result.notFound,
                )
                val remainingKeys = selectedKeys - handledKeys
                mutableState.update { current ->
                    current.copy(
                        selectionMode = remainingKeys.isNotEmpty(),
                        selectedSessionKeys = remainingKeys,
                        deletingSelection = false,
                        deletingSessionKeys = current.deletingSessionKeys - selectedKeys,
                        error = if (remainingKeys.isEmpty()) {
                            ""
                        } else {
                            "有 ${remainingKeys.size} 个会话未能删除，请重试。"
                        },
                    )
                }
                rebuildPagerIfNeeded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val recovered = try {
                    repository.listSessions(snapshot.scopedRunId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                mutableState.update { current ->
                    if (recovered == null) {
                        current.copy(
                            deletingSelection = false,
                            deletingSessionKeys = current.deletingSessionKeys - selectedKeys,
                            error = error.readableMessage("批量删除失败，请稍后重试。"),
                        )
                    } else {
                        val remoteKeys = recovered.mapTo(mutableSetOf(), DialogueSessionDto::key)
                        val remainingKeys = selectedKeys.intersect(remoteKeys)
                        val deletedCount = selectedKeys.size - remainingKeys.size
                        current.copy(
                            selectionMode = remainingKeys.isNotEmpty(),
                            selectedSessionKeys = remainingKeys,
                            deletingSelection = false,
                            deletingSessionKeys = current.deletingSessionKeys - selectedKeys,
                            error = when {
                                remainingKeys.isEmpty() -> ""
                                deletedCount > 0 -> "已删除 $deletedCount 个，仍有 ${remainingKeys.size} 个未删除，请重试。"
                                else -> error.readableMessage("批量删除失败，请稍后重试。")
                            },
                        )
                    }
                }
                rebuildPagerIfNeeded()
            }
        }
    }

    fun clearError() {
        mutableState.update { it.copy(error = "") }
    }

    /** 为当前可见会话补齐缺失的头像（Paging 加载新页面时由 UI 触发）。 */
    fun ensureAvatars(sessions: List<DialogueSessionDto>) {
        val targets = sessions.asSequence()
            .flatMap { session ->
                session.characterAvatars.asSequence().map { (character, version) ->
                    SessionAvatarReference(session.runId, character, version)
                }
            }
            .filter { it.runId.isNotBlank() && it.character.isNotBlank() && it.version.isNotBlank() }
            .filter { it.key !in loadedAvatarKeys }
            .distinct()
            .toList()
        if (targets.isEmpty()) return
        avatarJob?.cancel()
        avatarJob = viewModelScope.launch {
            targets.forEach { avatar ->
                loadedAvatarKeys += avatar.key
                val bytes = try {
                    repository.getPersonaAvatar(avatar.runId, avatar.character, avatar.version)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                } ?: return@forEach
                mutableState.update { current ->
                    current.copy(avatarBytes = current.avatarBytes + (avatar.key to bytes))
                }
            }
        }
    }

    private fun selectInitialRunId(runs: List<RunManifestDto>, preferredRunId: String?): String {
        val eligible = runs.filter { it.availableCharacters.isNotEmpty() }
        return eligible.firstOrNull { it.runId == preferredRunId }?.runId
            ?: eligible.firstOrNull()?.runId
            ?: ""
    }

    private fun newDraftForRun(runs: List<RunManifestDto>, runId: String): NewSessionDraft {
        val characters = runs.firstOrNull { it.runId == runId }?.availableCharacters.orEmpty()
        return NewSessionDraft(
            runId = runId,
            participants = characters.toSet(),
            controlledCharacter = characters.firstOrNull().orEmpty(),
        )
    }

    private suspend fun loadOptionalCards(kind: ReusableCardKind): List<ReusableCardDto> = try {
        repository.listReusableCards(kind)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        emptyList()
    }

    /**
     * 查询身份变化（runId/搜索词/排序）时重建 Pager；否则仅刷新，保留已加载缓存与滚动位置。
     */
    private fun rebuildPagerIfNeeded() {
        val current = state.value
        val params = PagerParams(
            runId = current.scopedRunId,
            query = current.searchQuery.trim(),
            sort = current.sort.serverValue(),
        )
        if (params == pagerParams) {
            currentSource?.invalidate()
            return
        }
        pagerParams = params
        val newPager = Pager(
            config = PagingConfig(
                pageSize = 30,
                initialLoadSize = 30,
                prefetchDistance = 10,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = {
                val source = SessionsPagingSource(
                    fetcher = { offset, limit ->
                        repository.listSessionsPage(
                            runId = params.runId,
                            offset = offset,
                            limit = limit,
                            query = params.query,
                            sort = params.sort,
                        )
                    },
                    onFirstPage = { total ->
                        mutableState.update { it.copy(totalSessions = total) }
                    },
                )
                currentSource = source
                source
            },
        )
        pagerFlow.value = newPager
    }

    private fun SessionsUiState.afterSessionCreated(created: DialogueSessionDto): SessionsUiState =
        copy(
            creating = false,
            createDialogVisible = false,
            createdSession = created,
            error = "",
        )

    private fun DialogueSessionDto.matches(draft: NewSessionDraft): Boolean =
        runId == draft.runId &&
            mode == draft.mode &&
            participants.toSet() == draft.participants &&
            (draft.mode != "act" || controlledCharacter == draft.controlledCharacter)

    private fun createValidationMessage(draft: NewSessionDraft): String = when {
        draft.runId.isBlank() -> "请选择一本已经完成人物蒸馏的书。"
        draft.participants.isEmpty() -> "请至少选择一位参与人物。"
        draft.mode == "act" && draft.controlledCharacter !in draft.participants -> "请选择你要扮演的人物。"
        draft.mode == "insert" && draft.selfName.isBlank() -> "请填写你进入故事后使用的名字。"
        draft.mode == "insert" && draft.selfIdentity.isBlank() -> "请填写你在当前场景中的身份。"
        else -> "当前会话设置不完整。"
    }

    private companion object {
        val dialogueModes = setOf("observe", "act", "insert")
    }
}

private data class SceneRecommendationContext(
    val runId: String,
    val mode: String,
    val participants: Set<String>,
    val sceneCardId: String,
    val openingPresetId: String,
)

private fun NewSessionDraft.sceneRecommendationContext() = SceneRecommendationContext(
    runId = runId,
    mode = mode,
    participants = participants,
    sceneCardId = sceneCardId,
    openingPresetId = openingPresetId,
)

private data class LoadedSessionResources(
    val runs: List<RunManifestDto>,
    val sceneCards: List<ReusableCardDto>,
    val selfCards: List<ReusableCardDto>,
    val openingPresets: List<ReusableCardDto>,
)

private data class SessionAvatarReference(
    val runId: String,
    val character: String,
    val version: String,
) {
    val key: String
        get() = "$runId|$character"
}

private data class PagerParams(
    val runId: String?,
    val query: String,
    val sort: String,
)

internal fun SessionsSort.serverValue(): String = when (this) {
    SessionsSort.Recent -> "recent"
    SessionsSort.Title -> "title"
}

internal fun toggleVisibleSelection(
    selectedKeys: Set<String>,
    visibleKeys: Set<String>,
): Set<String> = if (visibleKeys.isNotEmpty() && visibleKeys.all(selectedKeys::contains)) {
    selectedKeys - visibleKeys
} else {
    selectedKeys + visibleKeys
}

internal fun handledSessionKeys(
    selectedKeys: Set<String>,
    refs: List<SessionRefDto>,
): Set<String> = refs
    .mapTo(mutableSetOf()) { "${it.runId}::${it.sessionId}" }
    .intersect(selectedKeys)

internal val DialogueSessionDto.key: String
    get() = "$runId::$sessionId"

private fun JsonObject.stringValue(key: String): String = this[key]
    ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    .orEmpty()

private fun Throwable.readableMessage(fallback: String): String = message
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: fallback

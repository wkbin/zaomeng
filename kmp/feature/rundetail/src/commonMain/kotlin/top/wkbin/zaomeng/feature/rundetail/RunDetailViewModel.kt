package top.wkbin.zaomeng.feature.rundetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.data.PersonaRepository
import top.wkbin.zaomeng.data.RunRepository
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.UpdateCardGreetingRequest
import top.wkbin.zaomeng.data.api.UpdateSillyTavernCardMetadataRequest
import top.wkbin.zaomeng.domain.sessions.CreateDialogueSessionCommand
import top.wkbin.zaomeng.domain.sessions.CreateDialogueSessionUseCase
import top.wkbin.zaomeng.data.api.ExportedRunPackage
import top.wkbin.zaomeng.data.api.ManagedWorldBookDto
import top.wkbin.zaomeng.data.api.ManagedWorldBooksDto
import top.wkbin.zaomeng.data.api.SetWorldBookEntryRequest
import top.wkbin.zaomeng.data.api.EditWorldBookEntryRequest
import top.wkbin.zaomeng.data.api.CreateWorldBookEntryRequest
import top.wkbin.zaomeng.data.api.SetWorldBookRecursionRequest
import top.wkbin.zaomeng.data.api.WorldBookTestDto
import top.wkbin.zaomeng.data.api.TestWorldBooksRequest
import top.wkbin.zaomeng.platform.cropAvatarBytes
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.SillyTavernPng
import top.wkbin.zaomeng.platform.platformIoDispatcher
import top.wkbin.zaomeng.ui.graphics.encodeAvatarAsPng
import okio.FileSystem
import okio.Path
import okio.Sink
import okio.buffer
import okio.use
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.domain.run.LoadRunReviewUseCase
import top.wkbin.zaomeng.domain.run.RunReviewOverview
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RunDetailUiState(
    val run: RunManifestDto? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val stopping: Boolean = false,
    val redistilling: Boolean = false,
    val exporting: Boolean = false,
    val deleting: Boolean = false,
    val deleted: Boolean = false,
    val error: String = "",
    val message: String = "",
    val exportedPackage: ExportedRunPackage? = null,
    val exportRequestId: Long = 0,
    val exportDestinationPending: Boolean = false,
    val avatarBytes: Map<String, ByteArray> = emptyMap(),
    val updatingAvatar: String = "",
    val reviewLoading: Boolean = false,
    val reviewOverview: RunReviewOverview? = null,
    val startingCardChat: Boolean = false,
    val cardChatError: String = "",
    val startedCardChat: DialogueSessionDto? = null,
)

data class AvatarCrop(
    val left: Int,
    val top: Int,
    val side: Int,
)

data class WorldBooksUiState(
    val data: ManagedWorldBooksDto? = null,
    val busy: Boolean = false,
    val error: String = "",
    val result: WorldBookTestDto? = null,
)

class RunDetailViewModel(
    private val runs: RunRepository,
    private val persona: PersonaRepository,
    val runId: String,
    private val cacheDir: Path,
    private val distillationForeground: DistillationForeground,
    private val loadRunReview: LoadRunReviewUseCase,
    private val sessions: SessionRepository,
    private val createDialogueSession: CreateDialogueSessionUseCase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RunDetailUiState())
    val state: StateFlow<RunDetailUiState> = mutableState.asStateFlow()
    private val mutableWorldBooks = MutableStateFlow(WorldBooksUiState())
    val worldBooks: StateFlow<WorldBooksUiState> = mutableWorldBooks.asStateFlow()

    private var loadJob: Job? = null
    private var pollingJob: Job? = null
    private var reviewJob: Job? = null

    init {
        require(runId.isNotBlank()) { "runId 不能为空。" }
        load()
    }

    fun load() = loadRun(refreshManifest = false)

    fun refresh() = loadRun(refreshManifest = true)

    suspend fun loadCardGreetings() = sessions.getCardGreetings(runId)

    suspend fun updateCardGreeting(request: UpdateCardGreetingRequest) = sessions.updateCardGreeting(runId, request)

    suspend fun loadCardProfile(character: String) = persona.getPersona(runId, character)

    suspend fun updateCardMetadata(character: String, request: UpdateSillyTavernCardMetadataRequest) =
        persona.updateCardMetadata(runId, character, request)

    fun startCardChat(character: String, greetingIndex: Int?) {
        val snapshot = state.value
        val run = snapshot.run ?: return
        if (snapshot.startingCardChat || snapshot.startedCardChat != null ||
            !run.isSillyTavernCharacterCard || character !in run.availableCharacters
        ) return

        mutableState.update { it.copy(startingCardChat = true, cardChatError = "") }
        viewModelScope.launch {
            try {
                val chat = createDialogueSession(
                    CreateDialogueSessionCommand(
                        runId = runId,
                        mode = "insert",
                        participants = listOf(character),
                        cardGreetingIndex = greetingIndex,
                    ),
                )
                mutableState.update { it.copy(startedCardChat = chat) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(cardChatError = failure.message ?: "无法开始对话，请重试。") }
            } finally {
                mutableState.update { it.copy(startingCardChat = false) }
            }
        }
    }

    fun consumeStartedCardChat() = mutableState.update { it.copy(startedCardChat = null) }

    fun loadWorldBooks() = worldBookAction {
        it.copy(data = runs.listWorldBooks(runId), result = null)
    }

    fun testWorldBooks(input: TestWorldBooksRequest) = worldBookAction {
        it.copy(result = runs.testWorldBooks(runId, input))
    }

    fun clearWorldBookTest() = mutableWorldBooks.update { it.copy(result = null) }

    fun setWorldBookEntry(book: ManagedWorldBookDto, index: Int, enabled: Boolean) = worldBookAction {
        val data = runs.setWorldBookEntry(runId, book.source, index, SetWorldBookEntryRequest(book.revision, enabled))
        it.copy(data = data, result = null)
    }

    fun removeWorldBook(book: ManagedWorldBookDto) = worldBookAction {
        it.copy(data = runs.removeStandaloneWorldBook(runId, book.revision), result = null)
    }

    fun setWorldBookRecursion(book: ManagedWorldBookDto, enabled: Boolean) = worldBookAction {
        val data = runs.setWorldBookRecursion(runId, book.source, SetWorldBookRecursionRequest(book.revision, enabled))
        it.copy(data = data, result = null)
    }

    fun editWorldBookEntry(source: String, index: Int, request: EditWorldBookEntryRequest, onSaved: () -> Unit) =
        worldBookAction(onSuccess = onSaved) {
            it.copy(data = runs.editWorldBookEntry(runId, source, index, request), result = null)
        }

    fun createWorldBookEntry(source: String, request: CreateWorldBookEntryRequest, onSaved: () -> Unit) =
        worldBookAction(onSuccess = onSaved) {
            it.copy(data = runs.createWorldBookEntry(runId, source, request), result = null)
        }

    fun deleteWorldBookEntry(source: String, index: Int, revision: String, onDeleted: () -> Unit) =
        worldBookAction(onSuccess = onDeleted) {
            it.copy(data = runs.deleteWorldBookEntry(runId, source, index, revision), result = null)
        }

    fun clearWorldBookError() = mutableWorldBooks.update { it.copy(error = "") }

    private fun worldBookAction(onSuccess: () -> Unit = {}, action: suspend (WorldBooksUiState) -> WorldBooksUiState) {
        val current = mutableWorldBooks.value
        if (current.busy) return
        mutableWorldBooks.value = current.copy(busy = true, error = "")
        viewModelScope.launch {
            try {
                mutableWorldBooks.value = action(current).copy(busy = false, error = "")
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableWorldBooks.update {
                    it.copy(busy = false, error = error.message ?: "世界书操作失败。")
                }
            }
        }
    }

    fun stop() {
        val run = state.value.run ?: return
        if (run.status != RUNNING_STATUS || state.value.stopping || run.control.stopRequested) return
        viewModelScope.launch {
            mutableState.update { it.copy(stopping = true, error = "", message = "") }
            try {
                val updated = runs.stopRun(runId)
                acceptRun(updated)
                mutableState.update {
                    it.copy(stopping = false, message = "停止请求已发送，正在等待当前步骤收尾。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val recovered = queryRunAfterUnknown()?.takeIf {
                    it.control.stopRequested || it.status != RUNNING_STATUS
                }
                if (recovered != null) {
                    acceptRun(recovered)
                    mutableState.update {
                        it.copy(stopping = false, message = "停止请求已发送，正在等待当前步骤收尾。")
                    }
                } else {
                    mutableState.update {
                        it.copy(stopping = false, error = error.message ?: "停止蒸馏失败。")
                    }
                }
            }
        }
    }

    fun redistillOriginalCharacters() {
        val run = state.value.run ?: return
        if (state.value.redistilling) return
        if (run.status == RUNNING_STATUS) {
            mutableState.update { it.copy(error = "请先停止当前蒸馏，再按原人物重新开始。", message = "") }
            return
        }
        val characters = run.lockedCharacters.ifEmpty { run.availableCharacters }.distinct()
        if (characters.isEmpty()) {
            mutableState.update { it.copy(error = "这份书卷里还没有可重新蒸馏的人物。", message = "") }
            return
        }

        viewModelScope.launch {
            mutableState.update { it.copy(redistilling = true, error = "", message = "") }
            try {
                val updated = runs.redistill(runId, characters)
                acceptRun(updated)
                mutableState.update {
                    it.copy(redistilling = false, message = "已按原人物名单重新开始蒸馏。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val recovered = queryRunAfterUnknown()?.takeIf { it.status == RUNNING_STATUS }
                if (recovered != null) {
                    acceptRun(recovered)
                    mutableState.update {
                        it.copy(redistilling = false, message = "已按原人物名单重新开始蒸馏。")
                    }
                } else {
                    mutableState.update {
                        it.copy(redistilling = false, error = error.message ?: "重新蒸馏失败。")
                    }
                }
            }
        }
    }

    fun resumeUnfinishedCharacters() {
        val run = state.value.run ?: return
        if (state.value.redistilling) return
        if (run.status == RUNNING_STATUS) {
            mutableState.update { it.copy(error = "请先停止当前蒸馏，再继续未完成人物。", message = "") }
            return
        }
        val unfinishedCount = run.lockedCharacters.count { it !in run.progress.completedCharacters }
        if (unfinishedCount == 0) {
            mutableState.update { it.copy(error = "这份书卷没有未完成的人物。", message = "") }
            return
        }

        viewModelScope.launch {
            mutableState.update { it.copy(redistilling = true, error = "", message = "") }
            try {
                val updated = runs.resumeDistill(runId)
                acceptRun(updated)
                mutableState.update {
                    it.copy(redistilling = false, message = "已从未完成人物继续蒸馏。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val recovered = queryRunAfterUnknown()?.takeIf { it.status == RUNNING_STATUS }
                if (recovered != null) {
                    acceptRun(recovered)
                    mutableState.update {
                        it.copy(redistilling = false, message = "已从未完成人物继续蒸馏。")
                    }
                } else {
                    mutableState.update {
                        it.copy(redistilling = false, error = error.message ?: "继续蒸馏失败。")
                    }
                }
            }
        }
    }

    fun exportRun(includeDialogue: Boolean = true) = prepareExport {
        runs.exportRun(runId, cacheDir, includeDialogue = includeDialogue)
    }

    fun exportSillyTavernCard(character: String) = prepareExport {
        runs.exportSillyTavernCard(runId, character, cacheDir)
    }

    fun exportSillyTavernPngCard(character: String, avatarVersion: String) = prepareExport {
        val avatar = requireNotNull(persona.getPersonaAvatar(runId, character, avatarVersion)) {
            "请先为角色添加头像。"
        }
        val exported = runs.exportSillyTavernCard(runId, character, cacheDir)
        try {
            withContext(platformIoDispatcher) {
                require(exported.byteCount in 1L..SillyTavernPng.MAX_JSON_BYTES.toLong()) {
                    "角色卡数据过大，无法生成可重新导入的 PNG。"
                }
                val image = if (SillyTavernPng.hasSignature(avatar)) avatar else encodeAvatarAsPng(avatar)
                val json = FileSystem.SYSTEM.read(exported.file) { readByteArray() }
                val spec = Json.parseToJsonElement(json.decodeToString()).jsonObject["spec"]?.jsonPrimitive?.content
                val png = SillyTavernPng.writeCard(image, json, isV3 = spec == "chara_card_v3")
                FileSystem.SYSTEM.write(exported.file) { write(png) }
                exported.copy(
                    filename = exported.filename.removeSuffix(".json") + ".png",
                    byteCount = png.size.toLong(),
                    mimeType = "image/png",
                )
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + platformIoDispatcher) {
                runCatching { FileSystem.SYSTEM.delete(exported.file) }
            }
            throw failure
        }
    }

    fun exportWorldBook(book: ManagedWorldBookDto) = prepareExport {
        val name = book.name.ifBlank { if (book.source == "character_book") "卡内世界书" else "独立世界书" }
        runs.exportWorldBook(runId, book.source, name, cacheDir)
    }

    private fun prepareExport(prepare: suspend () -> ExportedRunPackage) {
        if (state.value.exporting) return
        viewModelScope.launch {
            val staleExport = state.value.exportedPackage
            mutableState.update {
                it.copy(
                    exporting = true,
                    exportedPackage = null,
                    exportDestinationPending = false,
                    error = "",
                    message = "",
                )
            }
            withContext(platformIoDispatcher) { staleExport?.file?.let { runCatching { FileSystem.SYSTEM.delete(it) } } }
            var pendingExport: ExportedRunPackage? = null
            try {
                val exported = prepare()
                pendingExport = exported
                mutableState.update {
                    it.copy(
                        exporting = false,
                        exportedPackage = exported,
                        exportRequestId = it.exportRequestId + 1,
                        exportDestinationPending = true,
                        message = "导出文件已准备好，请选择保存位置。",
                    )
                }
                pendingExport = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(exporting = false, error = error.message ?: "准备导出文件失败。")
                }
            } finally {
                withContext(NonCancellable + platformIoDispatcher) { pendingExport?.file?.let { runCatching { FileSystem.SYSTEM.delete(it) } } }
            }
        }
    }

    fun consumeExportedPackage() {
        val exported = state.value.exportedPackage
        mutableState.update {
            it.copy(
                exportedPackage = null,
                exportDestinationPending = false,
                message = "",
            )
        }
        exported?.file?.let { runCatching { FileSystem.SYSTEM.delete(it) } }
    }

    fun consumeExportDestinationRequest(requestId: Long) {
        mutableState.update {
            if (it.exportRequestId == requestId) {
                it.copy(exportDestinationPending = false)
            } else {
                it
            }
        }
    }

    fun cancelExportDestination() {
        if (state.value.exportedPackage == null) return
        mutableState.update {
            it.copy(
                exportDestinationPending = false,
                error = "已取消选择保存位置。",
                message = "",
            )
        }
    }

    suspend fun saveExportedPackage(destination: Sink) {
        val exported = state.value.exportedPackage ?: return
        try {
            withContext(platformIoDispatcher) {
                FileSystem.SYSTEM.source(exported.file).buffer().use { source ->
                    destination.buffer().use { sink -> sink.writeAll(source) }
                }
            }
            withContext(platformIoDispatcher) { runCatching { FileSystem.SYSTEM.delete(exported.file) } }
            mutableState.update {
                it.copy(
                    exportedPackage = null,
                    exportDestinationPending = false,
                    error = "",
                    message = "已导出 ${exported.filename}。",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update {
                it.copy(
                    exportDestinationPending = false,
                    error = error.message ?: "导出文件写入失败。",
                )
            }
        }
    }

    fun retryExportDestination() {
        if (state.value.exportedPackage == null) return
        mutableState.update {
            it.copy(
                error = "",
                message = "请重新选择保存位置。",
                exportRequestId = it.exportRequestId + 1,
                exportDestinationPending = true,
            )
        }
    }

    fun deleteRun() {
        val run = state.value.run ?: return
        if (state.value.deleting) return
        if (run.status == RUNNING_STATUS) {
            mutableState.update { it.copy(error = "请先停止当前蒸馏，再删除这份书卷。") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(deleting = true, error = "", message = "") }
            try {
                runs.deleteRun(runId)
                markDeleted()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val deleted = try {
                    runs.listRuns().none { it.runId == runId }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    false
                }
                if (deleted) {
                    markDeleted()
                } else {
                    mutableState.update {
                        it.copy(deleting = false, error = error.message ?: "删除书卷失败。")
                    }
                }
            }
        }
    }

    fun dismissNotice() {
        mutableState.update { it.copy(error = "", message = "") }
    }

    fun updatePersonaAvatar(character: String, bytes: ByteArray, crop: AvatarCrop) {
        if (character.isBlank() || state.value.updatingAvatar.isNotBlank()) return
        viewModelScope.launch {
            mutableState.update { it.copy(updatingAvatar = character, error = "", message = "") }
            try {
                val cropped = withContext(Dispatchers.Default) {
                    cropAvatarBytes(bytes, crop.side, crop.left, crop.top)
                }
                persona.uploadPersonaAvatar(runId, character, cropped)
                val refreshed = runs.getRun(runId)
                acceptRun(refreshed)
                mutableState.update { it.copy(avatarBytes = it.avatarBytes + (character to cropped)) }
                loadAvatar(character, refreshed.artifactIndex.characters.firstOrNull { it.name == character }?.avatarVersion.orEmpty())
                mutableState.update { it.copy(updatingAvatar = "", message = "${character} 的头像已更新。") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(updatingAvatar = "", error = error.message ?: "头像更新失败。") }
            }
        }
    }

    private fun loadRun(refreshManifest: Boolean) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loading = it.run == null,
                    refreshing = it.run != null,
                    error = "",
                    message = "",
                )
            }
            try {
                val run = if (refreshManifest) runs.refreshRun(runId) else runs.getRun(runId)
                acceptRun(run)
                loadReviewOverview(run)
                run.artifactIndex.characters.forEach { persona -> loadAvatar(persona.name, persona.avatarVersion) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = error.message ?: "书卷详情读取失败。",
                    )
                }
            }
        }
    }

    private fun loadAvatar(character: String, version: String) {
        if (character.isBlank() || version.isBlank() || state.value.avatarBytes.containsKey(character)) return
        viewModelScope.launch {
            val bytes = runCatching { persona.getPersonaAvatar(runId, character, version) }.getOrNull() ?: return@launch
            mutableState.update { it.copy(avatarBytes = it.avatarBytes + (character to bytes)) }
        }
    }

    private fun acceptRun(run: RunManifestDto) {
        mutableState.update {
            it.copy(
                run = run,
                loading = false,
                refreshing = false,
                error = "",
            )
        }
        if (run.status == RUNNING_STATUS) {
            distillationForeground.start()
            startPolling()
        } else {
            stopPolling()
        }
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            try {
                while (isActive && state.value.run?.status == RUNNING_STATUS) {
                    delay(POLL_INTERVAL_MS)
                    try {
                        val run = runs.getRun(runId)
                        val wasRunning = state.value.run?.status == RUNNING_STATUS
                        mutableState.update { it.copy(run = run, error = "") }
                        if (run.status != RUNNING_STATUS) {
                            if (wasRunning) loadReviewOverview(run)
                            return@launch
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        mutableState.update {
                            it.copy(error = error.message ?: "进度更新暂时失败，将继续重试。")
                        }
                    }
                }
            } finally {
                val currentJob = currentCoroutineContext()[Job]
                if (pollingJob === currentJob) pollingJob = null
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun loadReviewOverview(run: RunManifestDto) {
        reviewJob?.cancel()
        if (run.status == RUNNING_STATUS || run.availableCharacters.isEmpty() || run.isSillyTavernCharacterCard) {
            mutableState.update { it.copy(reviewLoading = false, reviewOverview = null) }
            return
        }
        reviewJob = viewModelScope.launch {
            mutableState.update { it.copy(reviewLoading = true, reviewOverview = null) }
            val overview = loadRunReview(run)
            mutableState.update {
                it.copy(
                    reviewLoading = false,
                    reviewOverview = overview,
                )
            }
        }
    }

    private fun markDeleted() {
        stopPolling()
        mutableState.update { it.copy(deleting = false, deleted = true) }
    }

    private suspend fun queryRunAfterUnknown(): RunManifestDto? = try {
        runs.getRun(runId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    override fun onCleared() {
        pollingJob?.cancel()
        reviewJob?.cancel()
        state.value.exportedPackage?.file?.let { runCatching { FileSystem.SYSTEM.delete(it) } }
        super.onCleared()
    }

    private companion object {
        const val RUNNING_STATUS = "running"
        const val POLL_INTERVAL_MS = 2_000L
    }
}

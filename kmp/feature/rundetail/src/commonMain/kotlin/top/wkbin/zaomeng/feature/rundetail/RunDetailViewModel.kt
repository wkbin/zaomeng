package top.wkbin.zaomeng.feature.rundetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.data.PersonaRepository
import top.wkbin.zaomeng.data.RunRepository
import top.wkbin.zaomeng.data.api.ExportedRunPackage
import top.wkbin.zaomeng.platform.cropAvatarBytes
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.platformIoDispatcher
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
)

data class AvatarCrop(
    val left: Int,
    val top: Int,
    val side: Int,
)

class RunDetailViewModel(
    private val runs: RunRepository,
    private val persona: PersonaRepository,
    val runId: String,
    private val cacheDir: Path,
    private val distillationForeground: DistillationForeground,
    private val loadRunReview: LoadRunReviewUseCase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RunDetailUiState())
    val state: StateFlow<RunDetailUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var pollingJob: Job? = null
    private var reviewJob: Job? = null

    init {
        require(runId.isNotBlank()) { "runId 不能为空。" }
        load()
    }

    fun load() = loadRun(refreshManifest = false)

    fun refresh() = loadRun(refreshManifest = true)

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

    fun exportRun(includeDialogue: Boolean = true) {
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
                val exported = runs.exportRun(
                    runId,
                    cacheDir,
                    includeDialogue = includeDialogue,
                )
                pendingExport = exported
                mutableState.update {
                    it.copy(
                        exporting = false,
                        exportedPackage = exported,
                        exportRequestId = it.exportRequestId + 1,
                        exportDestinationPending = true,
                        message = "书卷包已经准备好，请选择保存位置。",
                    )
                }
                pendingExport = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(exporting = false, error = error.message ?: "导出书卷失败。")
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
                    message = "书卷已导出（不含聊天记录）。",
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
        if (run.status == RUNNING_STATUS || run.availableCharacters.isEmpty()) {
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

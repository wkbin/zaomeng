package top.wkbin.zaomeng.feature.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.backend.BackendState
import top.wkbin.zaomeng.data.AppRuntimeRepository
import top.wkbin.zaomeng.data.ModelSettingsRepository
import top.wkbin.zaomeng.data.RunRepository
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.platform.DistillationForeground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BookshelfFilter(val label: String) {
    All("全部"),
    Ready("可使用"),
    Running("蒸馏中"),
    Draft("待蒸馏"),
    NeedsAttention("需处理"),
}

enum class BookshelfSort(val label: String) {
    Recent("按最近"),
    Title("按书名"),
}

data class BookshelfUiState(
    val backendState: BackendState = BackendState.Idle,
    val runs: List<RunManifestDto> = emptyList(),
    val loadingRuns: Boolean = false,
    val refreshing: Boolean = false,
    val stoppingTasks: Boolean = false,
    val modelConfigured: Boolean? = null,
    val activeModelLabel: String = "",
    val searchQuery: String = "",
    val filter: BookshelfFilter = BookshelfFilter.All,
    val sort: BookshelfSort = BookshelfSort.Recent,
    val recoveredRuns: List<RunManifestDto> = emptyList(),
    val dismissedRecoveredRunIds: Set<String> = emptySet(),
    val resumingRecoveredRunId: String = "",
    val error: String = "",
)

class BookshelfViewModel(
    private val appRuntime: AppRuntimeRepository,
    private val modelSettings: ModelSettingsRepository,
    private val runs: RunRepository,
    private val distillationForeground: DistillationForeground,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BookshelfUiState())
    val state: StateFlow<BookshelfUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var modelConfigurationJob: Job? = null

    init {
        appRuntime.startBackend()
        viewModelScope.launch {
            appRuntime.backendState.collectLatest { backendState ->
                mutableState.update {
                    it.copy(
                        backendState = backendState,
                        error = if (backendState is BackendState.Failed) backendState.message else it.error,
                    )
                }
                if (backendState is BackendState.Ready) {
                    loadRuns(manualRefresh = false)
                    refreshModelConfiguration()
                }
            }
        }
    }

    fun refresh() {
        if (state.value.backendState is BackendState.Ready) {
            loadRuns(manualRefresh = true)
        } else {
            retryBackend()
        }
    }

    /** Refresh after this retained destination returns from import or detail. */
    fun refreshWhenResumed() {
        if (state.value.backendState is BackendState.Ready) {
            loadRuns(manualRefresh = true)
            refreshModelConfiguration()
        }
    }

    private fun refreshModelConfiguration() {
        if (modelConfigurationJob?.isActive == true) return
        modelConfigurationJob = viewModelScope.launch {
            try {
                val settings = modelSettings.getModelSettings()
                val activeProfile = settings.profiles.firstOrNull { it.profileId == settings.activeProfileId }
                val label = activeProfile?.let { profile -> profile.name.ifBlank { profile.model } }
                    ?: settings.model
                mutableState.update {
                    it.copy(
                        modelConfigured = settings.configured,
                        activeModelLabel = label,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The book list remains usable if this secondary check cannot be read.
                mutableState.update { it.copy(modelConfigured = null, activeModelLabel = "") }
            }
        }
    }

    fun retryBackend() {
        loadJob?.cancel()
        loadJob = null
        mutableState.update {
            it.copy(
                backendState = BackendState.Idle,
                loadingRuns = false,
                refreshing = false,
                error = "",
            )
        }
        appRuntime.retryBackend()
    }

    fun dismissError() {
        mutableState.update { it.copy(error = "") }
    }

    fun dismissRecoveredRun(runId: String) {
        if (runId.isBlank()) return
        mutableState.update {
            it.copy(
                recoveredRuns = it.recoveredRuns.filterNot { run -> run.runId == runId },
                dismissedRecoveredRunIds = it.dismissedRecoveredRunIds + runId,
            )
        }
    }

    fun resumeRecoveredRun(runId: String) {
        if (runId.isBlank() || state.value.resumingRecoveredRunId.isNotBlank()) return
        if (state.value.recoveredRuns.none { it.runId == runId }) return
        viewModelScope.launch {
            mutableState.update { it.copy(resumingRecoveredRunId = runId, error = "") }
            try {
                val updated = runs.resumeDistill(runId)
                if (updated.status != RUNNING_STATUS) {
                    mutableState.update {
                        it.copy(
                            resumingRecoveredRunId = "",
                            error = "任务未能恢复为运行状态，请打开书卷重试。",
                        )
                    }
                } else {
                    distillationForeground.start()
                    mutableState.update { current ->
                        current.copy(
                            runs = current.runs.map { run ->
                                if (run.runId == runId) updated else run
                            },
                            recoveredRuns = current.recoveredRuns.filterNot { run -> run.runId == runId },
                            dismissedRecoveredRunIds = current.dismissedRecoveredRunIds + runId,
                            resumingRecoveredRunId = "",
                            error = "",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        resumingRecoveredRunId = "",
                        error = error.message ?: "继续蒸馏失败。",
                    )
                }
            }
        }
    }

    fun updateSearchQuery(value: String) {
        mutableState.update { it.copy(searchQuery = value.take(MAX_SEARCH_LENGTH)) }
    }

    fun selectFilter(filter: BookshelfFilter) {
        mutableState.update { it.copy(filter = filter) }
    }

    fun toggleSort() {
        mutableState.update {
            it.copy(sort = if (it.sort == BookshelfSort.Recent) BookshelfSort.Title else BookshelfSort.Recent)
        }
    }

    fun clearFilters() {
        mutableState.update { it.copy(searchQuery = "", filter = BookshelfFilter.All) }
    }

    fun stopRunningTasks() {
        val runningIds = state.value.runs.filter { it.status == RUNNING_STATUS }.map(RunManifestDto::runId)
        if (runningIds.isEmpty() || state.value.stoppingTasks) return
        viewModelScope.launch {
            mutableState.update { it.copy(stoppingTasks = true, error = "") }
            distillationForeground.stopAll()
            try {
                val stopped = runningIds.map { runs.stopRun(it) }
                mutableState.update { current ->
                    current.copy(
                        runs = current.runs.map { run -> stopped.firstOrNull { it.runId == run.runId } ?: run },
                        stoppingTasks = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(stoppingTasks = false, error = error.message ?: "停止蒸馏任务失败。")
                }
            }
        }
    }

    private fun loadRuns(manualRefresh: Boolean) {
        if (loadJob?.isActive == true) return
        val initialLoad = state.value.runs.isEmpty() && !manualRefresh
        loadJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loadingRuns = initialLoad,
                    refreshing = !initialLoad,
                    error = "",
                )
            }
            try {
                val runList = runs.listRuns()
                val dismissed = state.value.dismissedRecoveredRunIds
                val recovered = recoverableRuns(runList, dismissed)
                if (runList.any { it.status == RUNNING_STATUS }) {
                    distillationForeground.start()
                }
                mutableState.update {
                    it.copy(
                        runs = runList.sortedByDescending(RunManifestDto::updatedAt),
                        recoveredRuns = recovered,
                        loadingRuns = false,
                        refreshing = false,
                        error = "",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loadingRuns = false,
                        refreshing = false,
                        error = error.message ?: "书架读取失败，请稍后重试。",
                    )
                }
            }
        }
    }

    private companion object {
        const val RUNNING_STATUS = "running"
        const val MAX_SEARCH_LENGTH = 120
    }
}

internal fun recoverableRuns(
    runs: List<RunManifestDto>,
    dismissedRecoveredRunIds: Set<String> = emptySet(),
): List<RunManifestDto> = runs.filter { run ->
    run.isInterrupted && run.runId !in dismissedRecoveredRunIds
}

internal fun filterBookshelfRuns(state: BookshelfUiState): List<RunManifestDto> {
    val query = state.searchQuery.trim().lowercase()
    val statusMatches: (RunManifestDto) -> Boolean = when (state.filter) {
        BookshelfFilter.All -> { _ -> true }
        BookshelfFilter.Ready -> { run -> run.status == "ready" }
        BookshelfFilter.Running -> { run -> run.status == "running" }
        BookshelfFilter.Draft -> { run -> run.status == "draft" }
        BookshelfFilter.NeedsAttention -> { run -> run.status == "failed" || run.status == "stopped" }
    }
    val filtered = state.runs.filter { run ->
        statusMatches(run) && (
            query.isBlank() ||
                run.title.lowercase().contains(query) ||
                run.novelSources.any { it.sourceName.lowercase().contains(query) } ||
                run.lockedCharacters.any { it.lowercase().contains(query) }
            )
    }
    return when (state.sort) {
        BookshelfSort.Recent -> filtered
        BookshelfSort.Title -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
}

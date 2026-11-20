package top.wkbin.zaomeng.feature.storyrecap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.SessionProjectionDto
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

data class StoryRecapUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val session: DialogueSessionDto? = null,
    val projectionStatus: String = "missing",
    val error: String = "",
)

class StoryRecapViewModel(
    private val repository: SessionRepository,
    val runId: String,
    val sessionId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(StoryRecapUiState())
    val state: StateFlow<StoryRecapUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        require(runId.isNotBlank()) { "runId 不能为空。" }
        require(sessionId.isNotBlank()) { "sessionId 不能为空。" }
        load()
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loading = it.session == null,
                    refreshing = it.session != null,
                    error = "",
                )
            }
            try {
                val initial = repository.getSessionProjection(runId, sessionId)
                val projection = if (initial.status in setOf("stale", "missing", "failed")) {
                    repository.rebuildSessionProjection(runId, sessionId)
                } else {
                    initial
                }
                applyProjection(awaitProjection(projection))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        projectionStatus = "failed",
                        error = error.message ?: "剧情复盘读取失败。",
                    )
                }
            }
        }
    }

    fun rebuild() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(refreshing = true, error = "") }
            try {
                applyProjection(awaitProjection(repository.rebuildSessionProjection(runId, sessionId)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        projectionStatus = "failed",
                        error = error.message ?: "剧情复盘重建失败。",
                    )
                }
            }
        }
    }

    private suspend fun awaitProjection(initial: SessionProjectionDto): SessionProjectionDto {
        var current = initial
        val deadline = TimeSource.Monotonic.markNow() + 60.seconds
        while (current.status == "building") {
            if (deadline.hasPassedNow()) {
                return current.copy(
                    status = "failed",
                    error = "剧情复盘重建超时，请稍后重试。",
                )
            }
            delay(500)
            current = repository.getSessionProjection(runId, sessionId)
        }
        return current
    }

    private fun applyProjection(projection: SessionProjectionDto) {
        mutableState.update {
            it.copy(
                loading = false,
                refreshing = false,
                session = projection.session,
                projectionStatus = projection.status,
                error = projection.error,
            )
        }
    }
}

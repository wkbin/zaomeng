package top.wkbin.zaomeng.feature.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.WorldMemoryRepository
import top.wkbin.zaomeng.data.api.SaveWorldFactRequest
import top.wkbin.zaomeng.data.api.WorldFactDto
import top.wkbin.zaomeng.data.api.WorldMemoryDto

data class WorldTimelineUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val memory: WorldMemoryDto? = null,
    val savingFactId: String = "",
    val deletingFactId: String = "",
    val error: String = "",
    val message: String = "",
)

class WorldTimelineViewModel(
    private val repository: WorldMemoryRepository,
    val runId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(WorldTimelineUiState())
    val state: StateFlow<WorldTimelineUiState> = mutableState.asStateFlow()

    init {
        require(runId.isNotBlank()) { "runId 不能为空。" }
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loading = it.memory == null,
                    refreshing = it.memory != null,
                    error = "",
                )
            }
            try {
                val memory = repository.getWorldMemory(runId)
                mutableState.update {
                    it.copy(loading = false, refreshing = false, memory = memory)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = error.message ?: "故事记忆读取失败。",
                    )
                }
            }
        }
    }

    fun save(fact: WorldFactDto?, request: SaveWorldFactRequest) {
        if (state.value.savingFactId.isNotBlank()) return
        val operationId = fact?.factId ?: "new"
        viewModelScope.launch {
            mutableState.update { it.copy(savingFactId = operationId, error = "", message = "") }
            try {
                repository.saveWorldFact(runId, fact?.factId.orEmpty(), request)
                val memory = repository.getWorldMemory(runId)
                mutableState.update {
                    it.copy(
                        savingFactId = "",
                        memory = memory,
                        message = if (fact == null) "事实已添加。" else "事实已保存。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(savingFactId = "", error = error.message ?: "事实保存失败。")
                }
            }
        }
    }

    fun toggleLocked(fact: WorldFactDto) {
        save(fact, fact.toRequest(locked = !fact.locked))
    }

    fun delete(fact: WorldFactDto) {
        if (state.value.deletingFactId.isNotBlank()) return
        viewModelScope.launch {
            mutableState.update { it.copy(deletingFactId = fact.factId, error = "", message = "") }
            try {
                repository.deleteWorldFact(runId, fact.factId)
                val memory = repository.getWorldMemory(runId)
                mutableState.update {
                    it.copy(deletingFactId = "", memory = memory, message = "事实已删除。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(deletingFactId = "", error = error.message ?: "事实删除失败。")
                }
            }
        }
    }

    fun dismissNotice() {
        mutableState.update { it.copy(error = "", message = "") }
    }
}

internal fun WorldFactDto.toRequest(
    locked: Boolean = this.locked,
) = SaveWorldFactRequest(
    category = category,
    summary = summary,
    characters = characters,
    location = location,
    timeHint = timeHint,
    locked = locked,
    active = active,
)

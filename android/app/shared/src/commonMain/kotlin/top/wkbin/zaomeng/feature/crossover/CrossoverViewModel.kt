package top.wkbin.zaomeng.feature.crossover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.CrossoverParticipantRequest
import top.wkbin.zaomeng.data.api.RunManifestDto

data class CrossoverChoice(val runId: String, val runTitle: String, val character: String)

internal const val CROSSOVER_MAX_PARTICIPANTS = 8

data class CrossoverUiState(
    val runs: List<RunManifestDto> = emptyList(),
    val selected: List<CrossoverChoice> = emptyList(),
    val title: String = "",
    val worldSetting: String = "",
    val loading: Boolean = true,
    val creating: Boolean = false,
    val error: String = "",
    val createdRunId: String = "",
)

class CrossoverViewModel(private val repository: ZaomengRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(CrossoverUiState())
    val state: StateFlow<CrossoverUiState> = mutableState.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        mutableState.update { it.copy(loading = true, error = "") }
        try {
            val runs = repository.listRuns().filter {
                it.betaFeature == null && it.availableCharacters.isNotEmpty() && it.status != "running"
            }
            mutableState.update { it.copy(runs = runs, loading = false) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(loading = false, error = error.message ?: "人物列表加载失败。") }
        }
    }

    fun updateTitle(value: String) = mutableState.update { it.copy(title = value.take(80), error = "") }
    fun updateWorldSetting(value: String) = mutableState.update { it.copy(worldSetting = value.take(1000), error = "") }

    fun toggle(choice: CrossoverChoice) = mutableState.update { state ->
        val exists = state.selected.any { it.runId == choice.runId && it.character == choice.character }
        when {
            exists -> state.copy(selected = state.selected.filterNot { it == choice }, error = "")
            state.selected.size >= CROSSOVER_MAX_PARTICIPANTS -> state.copy(error = "Beta 版本最多选择 8 名人物。")
            state.selected.any { it.character == choice.character } -> state.copy(error = "不同书卷中的同名人物不能同时加入。")
            else -> state.copy(selected = state.selected + choice, error = "")
        }
    }

    fun create() {
        val current = state.value
        if (current.title.isBlank() || current.selected.size !in 2..CROSSOVER_MAX_PARTICIPANTS || current.selected.map { it.runId }.distinct().size < 2 || current.creating) {
            mutableState.update { it.copy(error = "请填写空间名称，并从至少两个书卷选择 2 到 8 名人物。") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(creating = true, error = "") }
            try {
                val run = repository.createCrossoverSpace(
                    current.title.trim(),
                    current.worldSetting.trim(),
                    current.selected.map { CrossoverParticipantRequest(it.runId, it.character) },
                )
                mutableState.update { it.copy(creating = false, createdRunId = run.runId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(creating = false, error = error.message ?: "共演空间创建失败。") }
            }
        }
    }

    fun consumeCreatedRun() = mutableState.update { it.copy(createdRunId = "") }
}

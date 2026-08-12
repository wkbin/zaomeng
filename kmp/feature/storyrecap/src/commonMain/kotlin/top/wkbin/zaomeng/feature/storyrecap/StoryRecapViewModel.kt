package top.wkbin.zaomeng.feature.storyrecap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.api.DialogueSessionDto

data class StoryRecapUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val session: DialogueSessionDto? = null,
    val error: String = "",
)

class StoryRecapViewModel(
    private val repository: SessionRepository,
    val runId: String,
    val sessionId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(StoryRecapUiState())
    val state: StateFlow<StoryRecapUiState> = mutableState.asStateFlow()

    init {
        require(runId.isNotBlank()) { "runId 不能为空。" }
        require(sessionId.isNotBlank()) { "sessionId 不能为空。" }
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loading = it.session == null,
                    refreshing = it.session != null,
                    error = "",
                )
            }
            try {
                val session = repository.getSession(runId, sessionId, includeTranscript = true)
                mutableState.update {
                    it.copy(loading = false, refreshing = false, session = session)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = error.message ?: "剧情复盘读取失败。",
                    )
                }
            }
        }
    }
}

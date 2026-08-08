package top.wkbin.zaomeng.feature.relations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.RelationDetailsDto
import top.wkbin.zaomeng.data.api.RelationItemDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RelationsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val details: RelationDetailsDto? = null,
    val savingPairKey: String = "",
    val error: String = "",
    val message: String = "",
)

class RelationsViewModel(
    private val repository: ZaomengRepository,
    val runId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RelationsUiState())
    val state: StateFlow<RelationsUiState> = mutableState.asStateFlow()

    init {
        require(runId.isNotBlank()) { "runId 不能为空。" }
        load()
    }

    fun load() {
        if (state.value.loading && state.value.details != null) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loading = it.details == null,
                    refreshing = it.details != null,
                    error = "",
                    message = "",
                )
            }
            try {
                val details = repository.getRelations(runId)
                mutableState.update {
                    it.copy(loading = false, refreshing = false, details = details)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = error.message ?: "关系资料读取失败。",
                    )
                }
            }
        }
    }

    fun updateItem(pairKey: String, transform: (RelationItemDto) -> RelationItemDto) {
        if (state.value.savingPairKey.isNotBlank()) return
        mutableState.update { current ->
            val details = current.details ?: return@update current
            current.copy(
                details = details.copy(
                    items = details.items.map { item ->
                        if (item.pairKey == pairKey) transform(item) else item
                    },
                ),
                error = "",
                message = "",
            )
        }
    }

    fun save(pairKey: String) {
        val item = state.value.details?.items?.firstOrNull { it.pairKey == pairKey } ?: return
        if (state.value.savingPairKey.isNotBlank()) return
        viewModelScope.launch {
            mutableState.update { it.copy(savingPairKey = pairKey, error = "", message = "") }
            try {
                val details = repository.updateRelation(runId, item)
                mutableState.update {
                    it.copy(
                        savingPairKey = "",
                        details = details,
                        message = "${item.characters.joinToString("、")}的关系已保存。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        savingPairKey = "",
                        error = error.message ?: "关系保存失败。",
                    )
                }
            }
        }
    }

    fun dismissNotice() {
        mutableState.update { it.copy(error = "", message = "") }
    }
}

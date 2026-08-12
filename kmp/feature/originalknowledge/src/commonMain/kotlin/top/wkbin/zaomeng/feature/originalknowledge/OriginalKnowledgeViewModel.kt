package top.wkbin.zaomeng.feature.originalknowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.OriginalKnowledgeRepository
import top.wkbin.zaomeng.data.RunRepository
import top.wkbin.zaomeng.data.api.OriginalKnowledgeEntryDto

data class OriginalKnowledgeUiState(
    val query: String = "",
    val pinnedOnly: Boolean = false,
    val characters: List<String> = emptyList(),
    val items: List<OriginalKnowledgeEntryDto> = emptyList(),
    val loading: Boolean = true,
    val rebuilding: Boolean = false,
    val busyEntryId: String = "",
    val error: String = "",
    val message: String = "",
)

class OriginalKnowledgeViewModel(
    private val originalKnowledge: OriginalKnowledgeRepository,
    private val runs: RunRepository,
    val runId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OriginalKnowledgeUiState())
    val state: StateFlow<OriginalKnowledgeUiState> = mutableState.asStateFlow()

    init {
        require(runId.isNotBlank()) { "runId 不能为空。" }
        loadInitial()
    }

    fun updateQuery(value: String) = mutableState.update { it.copy(query = value) }

    fun setPinnedOnly(value: Boolean) {
        mutableState.update { it.copy(pinnedOnly = value) }
        search()
    }

    fun search() {
        val snapshot = state.value
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = "", message = "") }
            try {
                val items = originalKnowledge.searchOriginalKnowledge(
                    runId = runId,
                    query = snapshot.query.trim(),
                    participants = snapshot.characters,
                    pinnedOnly = snapshot.pinnedOnly,
                )
                mutableState.update { it.copy(items = items, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(loading = false, error = error.message ?: "搜索原文证据失败。") }
            }
        }
    }

    fun rebuild() {
        if (state.value.rebuilding) return
        viewModelScope.launch {
            mutableState.update { it.copy(rebuilding = true, error = "", message = "") }
            try {
                originalKnowledge.rebuildOriginalKnowledge(runId)
                mutableState.update { it.copy(rebuilding = false, message = "原文证据索引已重建。") }
                search()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(rebuilding = false, error = error.message ?: "重建证据索引失败。") }
            }
        }
    }

    fun setPinned(entry: OriginalKnowledgeEntryDto, pinned: Boolean) = mutateEntry(entry.sourceId) {
        originalKnowledge.updateOriginalKnowledgePinned(runId, entry.sourceId, pinned)
        "已${if (pinned) "固定" else "取消固定"}证据 ${entry.sourceId}。"
    }

    fun saveBoundary(entry: OriginalKnowledgeEntryDto, visibility: String, knowers: List<String>) = mutateEntry(entry.sourceId) {
        originalKnowledge.updateOriginalKnowledgeBoundary(
            runId = runId,
            entryId = entry.sourceId,
            visibility = visibility,
            knowers = if (visibility in setOf("private", "scene")) knowers else emptyList(),
        )
        "已更新 ${entry.sourceId} 的角色可知边界。"
    }

    fun dismissNotice() = mutableState.update { it.copy(error = "", message = "") }

    private fun loadInitial() {
        viewModelScope.launch {
            try {
                val run = runs.getRun(runId)
                mutableState.update {
                    it.copy(characters = (run.availableCharacters + run.lockedCharacters).distinct())
                }
                search()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(loading = false, error = error.message ?: "载入书卷人物失败。") }
            }
        }
    }

    private fun mutateEntry(entryId: String, block: suspend () -> String) {
        if (entryId.isBlank() || state.value.busyEntryId.isNotBlank()) return
        viewModelScope.launch {
            mutableState.update { it.copy(busyEntryId = entryId, error = "", message = "") }
            try {
                val message = block()
                mutableState.update { it.copy(busyEntryId = "", message = message) }
                search()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(busyEntryId = "", error = error.message ?: "更新原文证据失败。") }
            }
        }
    }
}

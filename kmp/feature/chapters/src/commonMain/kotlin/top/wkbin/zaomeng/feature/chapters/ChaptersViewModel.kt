package top.wkbin.zaomeng.feature.chapters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.data.ChapterRepository
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.api.ChapterDto
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.ExportedChapterManuscript
import top.wkbin.zaomeng.data.api.SaveChapterRequest
import top.wkbin.zaomeng.data.api.SearchResultDto
import top.wkbin.zaomeng.data.api.AskBookResponseDto
import top.wkbin.zaomeng.platform.NovelConversionForeground
import top.wkbin.zaomeng.platform.platformIoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Sink
import okio.buffer
import okio.use

data class ChaptersUiState(
    val loading: Boolean = true,
    val chapters: List<ChapterDto> = emptyList(),
    val sessions: List<DialogueSessionDto> = emptyList(),
    val saving: Boolean = false,
    val openingChapterId: String = "",
    val syncingChapterId: String = "",
    val exporting: Boolean = false,
    val exported: ExportedChapterManuscript? = null,
    val exportRequestId: Long = 0,
    val navigationSessionId: String = "",
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchResults: List<SearchResultDto> = emptyList(),
    val bookQuestion: String = "",
    val askingBook: Boolean = false,
    val bookAnswer: AskBookResponseDto? = null,
    val error: String = "",
    val message: String = "",
)

class ChaptersViewModel(
    private val chapters: ChapterRepository,
    private val sessions: SessionRepository,
    val runId: String,
    private val cacheDir: Path,
    private val novelConversionForeground: NovelConversionForeground,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChaptersUiState())
    val state: StateFlow<ChaptersUiState> = mutableState.asStateFlow()

    init {
        require(runId.isNotBlank())
        load()
    }

    fun load() = viewModelScope.launch {
        mutableState.update { it.copy(loading = it.chapters.isEmpty(), error = "") }
        try {
            val chapters = chapters.listChapters(runId)
            val sessions = sessions.listSessions(runId)
            mutableState.update { it.copy(loading = false, chapters = chapters, sessions = sessions) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(loading = false, error = error.message ?: "无法读取章节工作台。") }
        }
    }

    fun updateSearchQuery(value: String) {
        mutableState.update { it.copy(searchQuery = value, searchResults = if (value.isBlank()) emptyList() else it.searchResults) }
    }

    fun search() {
        searchFor(state.value.searchQuery)
    }

    fun searchFor(value: String) {
        val query = value.trim()
        if (query.isBlank() || state.value.searching) return
        viewModelScope.launch {
            mutableState.update { it.copy(searchQuery = query, searching = true, error = "") }
            try {
                mutableState.update { it.copy(searching = false, searchResults = chapters.searchRunContent(runId, query)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(searching = false, error = error.message ?: "搜索失败。") }
            }
        }
    }

    fun updateBookQuestion(value: String) {
        mutableState.update { it.copy(bookQuestion = value.take(300), bookAnswer = null) }
    }

    fun askBook() {
        val question = state.value.bookQuestion.trim()
        if (question.isBlank() || state.value.askingBook) return
        viewModelScope.launch {
            mutableState.update { it.copy(askingBook = true, bookAnswer = null, error = "") }
            try {
                val answer = chapters.askBookQuestion(runId, question)
                mutableState.update { it.copy(askingBook = false, bookAnswer = answer) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(askingBook = false, error = error.message ?: "问书卷失败。") }
            }
        }
    }

    fun save(chapterId: String = "", title: String, goal: String, participants: String, content: String) {
        if (state.value.saving) return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, error = "", message = "") }
            try {
                chapters.saveChapter(
                    runId,
                    chapterId,
                    SaveChapterRequest(
                        title = title.trim(),
                        goal = goal.trim(),
                        participants = participants.split(',', '，', '\n', ';', '；').map(String::trim).filter(String::isNotBlank).distinct(),
                        content = content.trim(),
                    ),
                )
                val chapters = chapters.listChapters(runId)
                mutableState.update { it.copy(saving = false, chapters = chapters, message = "章节已保存。") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(saving = false, error = error.message ?: "保存章节失败。") }
            }
        }
    }

    fun archiveSession(sessionId: String, title: String = "") {
        if (state.value.saving || sessionId.isBlank()) return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, error = "", message = "") }
            try {
                chapters.archiveSessionAsChapter(runId, sessionId, title)
                mutableState.update {
                    it.copy(saving = false, chapters = chapters.listChapters(runId), message = "会话已归档为章节草稿。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(saving = false, error = error.message ?: "归档会话失败。") }
            }
        }
    }

    fun convertSession(sessionId: String, title: String = "") {
        if (state.value.saving || sessionId.isBlank()) return
        val started = novelConversionForeground.start(runId, sessionId, title)
        if (started) {
            mutableState.update {
                it.copy(message = "已放到后台生成小说章节，完成后会通过通知提醒。")
            }
        } else {
            mutableState.update {
                it.copy(error = "无法启动后台小说生成，请稍后重试。")
            }
        }
    }

    fun delete(chapterId: String) {
        if (state.value.saving) return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, error = "", message = "") }
            try {
                chapters.deleteChapter(runId, chapterId)
                mutableState.update { it.copy(saving = false, chapters = chapters.listChapters(runId), message = "章节已删除。") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(saving = false, error = error.message ?: "删除章节失败。") }
            }
        }
    }

    fun continueWriting(chapterId: String) {
        if (state.value.openingChapterId.isNotBlank() || state.value.saving) return
        viewModelScope.launch {
            mutableState.update { it.copy(openingChapterId = chapterId, error = "", message = "") }
            try {
                val session = chapters.continueChapter(runId, chapterId)
                mutableState.update {
                    it.copy(
                        openingChapterId = "",
                        navigationSessionId = session.sessionId,
                        chapters = chapters.listChapters(runId),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(openingChapterId = "", error = error.message ?: "无法为章节创建写作会话。") }
            }
        }
    }

    fun syncLatestSession(chapterId: String) {
        if (state.value.syncingChapterId.isNotBlank() || state.value.saving) return
        viewModelScope.launch {
            mutableState.update { it.copy(syncingChapterId = chapterId, error = "", message = "") }
            try {
                val chapter = chapters.syncChapterSession(runId, chapterId)
                mutableState.update {
                    it.copy(
                        syncingChapterId = "",
                        chapters = it.chapters.map { existing -> if (existing.chapterId == chapter.chapterId) chapter else existing },
                        message = "最新会话已收录回章节草稿。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(syncingChapterId = "", error = error.message ?: "收录会话失败。") }
            }
        }
    }

    fun move(chapterId: String, targetOrder: Int) {
        if (state.value.saving || state.value.openingChapterId.isNotBlank() || state.value.syncingChapterId.isNotBlank()) return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, error = "", message = "") }
            try {
                val chapters = chapters.reorderChapter(runId, chapterId, targetOrder)
                mutableState.update { it.copy(saving = false, chapters = chapters, message = "章节顺序已调整。") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(saving = false, error = error.message ?: "调整章节顺序失败。") }
            }
        }
    }

    fun consumeNavigationSession() {
        mutableState.update { it.copy(navigationSessionId = "") }
    }

    fun export(format: String) {
        if (state.value.exporting) return
        viewModelScope.launch {
            mutableState.update { it.copy(exporting = true, exported = null, error = "", message = "") }
            try {
                val exported = chapters.exportChapters(runId, format, cacheDir)
                mutableState.update { it.copy(exporting = false, exported = exported, exportRequestId = it.exportRequestId + 1) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(exporting = false, error = error.message ?: "导出章节失败。") }
            }
        }
    }

    suspend fun saveExport(destination: Sink) {
        val exported = state.value.exported ?: return
        try {
            withContext(platformIoDispatcher) {
                FileSystem.SYSTEM.source(exported.file).buffer().use { source ->
                    destination.buffer().use { sink -> sink.writeAll(source) }
                }
            }
            mutableState.update { it.copy(exported = null, message = "全书草稿已导出。") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(error = error.message ?: "写入导出文件失败。") }
        } finally {
            withContext(platformIoDispatcher) { runCatching { FileSystem.SYSTEM.delete(exported.file) } }
        }
    }

    fun hasNotificationPermission(): Boolean = novelConversionForeground.hasNotificationPermission()

    fun discardExport() {
        val file = state.value.exported?.file ?: return
        mutableState.update { it.copy(exported = null) }
        runCatching { FileSystem.SYSTEM.delete(file) }
    }
}

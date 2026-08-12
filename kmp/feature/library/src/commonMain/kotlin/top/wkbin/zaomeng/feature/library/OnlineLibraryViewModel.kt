package top.wkbin.zaomeng.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.RunRepository
import top.wkbin.zaomeng.data.library.OnlineLibraryBook
import top.wkbin.zaomeng.data.library.OnlineLibraryRepository
import top.wkbin.zaomeng.data.api.LibraryPackageImportDto

data class OnlineLibraryUiState(
    val books: List<OnlineLibraryBook> = emptyList(),
    val installedVersions: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val importingBookId: String = "",
    val downloadedBytes: Long = 0,
    val downloadTotalBytes: Long = 0,
    val error: String = "",
    val createdRunId: String = "",
)

class OnlineLibraryViewModel(
    private val libraryRepository: OnlineLibraryRepository,
    private val repository: RunRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OnlineLibraryUiState())
    val state: StateFlow<OnlineLibraryUiState> = mutableState.asStateFlow()
    private var importJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (state.value.loading) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = "") }
            try {
                val books = libraryRepository.listBooks()
                val installedVersions = repository.listRuns()
                    .mapNotNull { run -> run.importedFrom.onlineLibrary }
                    .filter { it.id.isNotBlank() && it.version.isNotBlank() }
                    .groupBy { it.id }
                    .mapValues { (_, sources) -> sources.maxOf { it.version } }
                mutableState.update {
                    it.copy(books = books, installedVersions = installedVersions, loading = false, error = "")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(loading = false, error = error.message ?: "在线书卷包加载失败。")
                }
            }
        }
    }

    fun importBook(book: OnlineLibraryBook) {
        if (book.id.isBlank() || importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    importingBookId = book.id,
                    downloadedBytes = 0,
                    downloadTotalBytes = book.sizeBytes,
                    error = "",
                )
            }
            try {
                val packageBytes = libraryRepository.downloadBook(book) { downloadedBytes, totalBytes ->
                    mutableState.update { current ->
                        if (current.importingBookId == book.id) {
                            current.copy(
                                downloadedBytes = downloadedBytes,
                                downloadTotalBytes = totalBytes,
                            )
                        } else {
                            current
                        }
                    }
                }
                val run = repository.importPackage(
                    filename = "${book.id}.zaomeng-run.zip",
                    bytes = packageBytes,
                    libraryPackage = LibraryPackageImportDto(
                        id = book.id,
                        title = book.title,
                        version = book.version,
                        downloadUrl = book.downloadUrl,
                        sha256 = book.sha256,
                    ),
                )
                mutableState.update {
                    it.copy(
                        importingBookId = "",
                        downloadedBytes = 0,
                        downloadTotalBytes = 0,
                        createdRunId = run.runId,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        importingBookId = "",
                        downloadedBytes = 0,
                        downloadTotalBytes = 0,
                        error = error.message ?: "书卷导入失败。",
                    )
                }
            }
        }
    }

    fun consumeCreatedRun() {
        mutableState.update { it.copy(createdRunId = "") }
    }

    fun cancelImport() {
        importJob?.cancel()
        mutableState.update { it.copy(importingBookId = "", downloadedBytes = 0, downloadTotalBytes = 0) }
    }
}

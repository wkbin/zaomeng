package top.wkbin.zaomeng.feature.redistill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.RedistillSegmentDto
import top.wkbin.zaomeng.data.api.RedistillSuggestionsDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.SamplingPlanDto
import top.wkbin.zaomeng.feature.importbook.ImportDocument
import top.wkbin.zaomeng.feature.importbook.ImportDocumentKind
import top.wkbin.zaomeng.feature.importbook.ImportDocumentLoader
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.feature.importbook.textStatistics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RedistillUiState(
    val loading: Boolean = true,
    val run: RunManifestDto? = null,
    val characters: String = "",
    val maxSentences: String = "120",
    val maxChars: String = "50000",
    val fileName: String = "",
    val fileSize: Long = 0,
    val sourceCharCount: Int = 0,
    val sourceSentenceCount: Int = 0,
    val readingFile: Boolean = false,
    val recommendationCharacter: String = "",
    val recommending: Boolean = false,
    val suggestions: RedistillSuggestionsDto? = null,
    val selectedSegmentId: String = "",
    val estimatingSampling: Boolean = false,
    val samplingPlan: SamplingPlanDto? = null,
    val samplingEstimateError: String = "",
    val submitting: Boolean = false,
    val completed: Boolean = false,
    val error: String = "",
) {
    val selectedSegment: RedistillSegmentDto?
        get() = suggestions?.segments?.firstOrNull { it.segmentId == selectedSegmentId }
}

class RedistillViewModel(
    private val repository: ZaomengRepository,
    val runId: String,
    private val distillationForeground: DistillationForeground,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RedistillUiState())
    val state: StateFlow<RedistillUiState> = mutableState.asStateFlow()
    private var selectedBytes: ByteArray? = null
    private var fileLoadJob: Job? = null
    private var samplingEstimateJob: Job? = null
    private var samplingEstimateRequestId = 0L

    init {
        require(runId.isNotBlank()) { "runId 不能为空。" }
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = "") }
            try {
                val run = repository.getRun(runId)
                val characters = run.lockedCharacters.ifEmpty { run.availableCharacters }.distinct()
                mutableState.update {
                    it.copy(
                        loading = false,
                        run = run,
                        characters = characters.joinToString("、"),
                        recommendationCharacter = characters.firstOrNull().orEmpty(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(loading = false, error = error.message ?: "书卷读取失败。")
                }
            }
        }
    }

    fun loadDocument(name: String, bytes: ByteArray) {
        fileLoadJob?.cancel()
        fileLoadJob = viewModelScope.launch {
            mutableState.update { it.copy(readingFile = true, error = "") }
            try {
                val document = withContext(Dispatchers.Default) {
                    ImportDocumentLoader.prepareImportDocument(
                        name,
                        bytes,
                        ImportDocumentKind.NovelText,
                    )
                }
                selectFile(document)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(readingFile = false, error = error.message ?: "文件读取失败。")
                }
            }
        }
    }

    private fun selectFile(document: ImportDocument) {
        val filename = document.fileName
        val bytes = document.bytes
        if (!filename.lowercase().endsWith(".txt")) {
            selectedBytes = null
            mutableState.update {
                it.copy(
                    fileName = "",
                    fileSize = 0,
                    sourceCharCount = 0,
                    sourceSentenceCount = 0,
                    readingFile = false,
                    samplingPlan = null,
                    error = "增量正文目前只支持 TXT。",
                )
            }
            scheduleSamplingEstimate()
            return
        }
        if (bytes.isEmpty()) {
            selectedBytes = null
            mutableState.update {
                it.copy(
                    fileName = "",
                    fileSize = 0,
                    sourceCharCount = 0,
                    sourceSentenceCount = 0,
                    readingFile = false,
                    samplingPlan = null,
                    error = "所选 TXT 文件为空。",
                )
            }
            scheduleSamplingEstimate()
            return
        }
        selectedBytes = bytes
        mutableState.update {
            it.copy(
                fileName = filename,
                fileSize = bytes.size.toLong(),
                sourceCharCount = document.charCount,
                sourceSentenceCount = document.sentenceCount,
                readingFile = false,
                selectedSegmentId = "",
                samplingPlan = null,
                samplingEstimateError = "",
                error = "",
            )
        }
        scheduleSamplingEstimate()
    }

    fun clearFile() {
        selectedBytes = null
        mutableState.update {
            it.copy(
                fileName = "",
                fileSize = 0,
                sourceCharCount = 0,
                sourceSentenceCount = 0,
                samplingPlan = null,
                samplingEstimateError = "",
                error = "",
            )
        }
        scheduleSamplingEstimate()
    }

    fun updateCharacters(value: String) {
        mutableState.update { it.copy(characters = value, samplingPlan = null, error = "") }
        scheduleSamplingEstimate()
    }
    fun updateMaxSentences(value: String) {
        mutableState.update { it.copy(maxSentences = value.filter(Char::isDigit), samplingPlan = null, error = "") }
        scheduleSamplingEstimate()
    }
    fun updateMaxChars(value: String) {
        mutableState.update { it.copy(maxChars = value.filter(Char::isDigit), samplingPlan = null, error = "") }
        scheduleSamplingEstimate()
    }
    fun selectRecommendationCharacter(value: String) {
        val fileSelected = selectedBytes != null
        mutableState.update {
            it.copy(
                recommendationCharacter = value,
                suggestions = null,
                selectedSegmentId = "",
                sourceCharCount = if (fileSelected) it.sourceCharCount else 0,
                sourceSentenceCount = if (fileSelected) it.sourceSentenceCount else 0,
                samplingPlan = null,
                error = "",
            )
        }
        scheduleSamplingEstimate()
    }

    fun recommendSegments() {
        val character = state.value.recommendationCharacter.trim()
        if (character.isBlank() || state.value.recommending) return
        viewModelScope.launch {
            val fileSelected = selectedBytes != null
            mutableState.update {
                it.copy(
                    recommending = true,
                    error = "",
                    selectedSegmentId = "",
                    sourceCharCount = if (fileSelected) it.sourceCharCount else 0,
                    sourceSentenceCount = if (fileSelected) it.sourceSentenceCount else 0,
                    samplingPlan = null,
                )
            }
            scheduleSamplingEstimate()
            try {
                val suggestions = repository.suggestRedistillSegments(runId, character)
                mutableState.update {
                    it.copy(recommending = false, suggestions = suggestions)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(recommending = false, error = error.message ?: "推荐片段失败。")
                }
            }
        }
    }

    fun selectSegment(segmentId: String) {
        selectedBytes = null
        mutableState.update {
            val nextSegmentId = if (it.selectedSegmentId == segmentId) "" else segmentId
            val statistics = it.suggestions?.segments
                ?.firstOrNull { segment -> segment.segmentId == nextSegmentId }
                ?.let { segment -> textStatistics(segment.fullText) }
            it.copy(
                fileName = "",
                fileSize = 0,
                selectedSegmentId = nextSegmentId,
                sourceCharCount = statistics?.charCount ?: 0,
                sourceSentenceCount = statistics?.sentenceCount ?: 0,
                samplingPlan = null,
                samplingEstimateError = "",
                error = "",
            )
        }
        scheduleSamplingEstimate()
    }

    fun refreshSamplingEstimate() = scheduleSamplingEstimate()

    private fun scheduleSamplingEstimate() {
        val requestId = ++samplingEstimateRequestId
        val snapshot = state.value
        samplingEstimateJob?.cancel()
        if (snapshot.sourceCharCount <= 0 || snapshot.sourceSentenceCount <= 0) {
            mutableState.update {
                it.copy(
                    estimatingSampling = false,
                    samplingPlan = null,
                    samplingEstimateError = "",
                )
            }
            return
        }
        samplingEstimateJob = viewModelScope.launch {
            delay(180)
            if (requestId != samplingEstimateRequestId) return@launch
            val current = state.value
            val maxSentences = current.maxSentences.toIntOrNull()?.coerceIn(20, 300) ?: 120
            val maxChars = current.maxChars.toIntOrNull()?.coerceIn(2_000, 200_000) ?: 50_000
            val characterCount = current.characters
                .split(',', '，', '、', ';', '；', '\n')
                .map(String::trim)
                .count(String::isNotBlank)
                .coerceAtLeast(1)
            mutableState.update {
                if (requestId == samplingEstimateRequestId) {
                    it.copy(estimatingSampling = true, samplingEstimateError = "")
                } else {
                    it
                }
            }
            try {
                val plan = repository.estimateSampling(
                    charCount = current.sourceCharCount,
                    sentenceCount = current.sourceSentenceCount,
                    characterCount = characterCount,
                    maxSentences = maxSentences,
                    maxChars = maxChars,
                )
                if (requestId != samplingEstimateRequestId) return@launch
                mutableState.update {
                    if (requestId == samplingEstimateRequestId) {
                        it.copy(
                            estimatingSampling = false,
                            samplingPlan = plan,
                            samplingEstimateError = "",
                        )
                    } else {
                        it
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (requestId != samplingEstimateRequestId) return@launch
                mutableState.update {
                    if (requestId == samplingEstimateRequestId) {
                        it.copy(
                            estimatingSampling = false,
                            samplingEstimateError = error.message ?: "无法生成取样预估。",
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun submit() {
        val snapshot = state.value
        if (snapshot.submitting) return
        val characters = snapshot.characters
            .split(',', '，', '、', ';', '；', '\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (characters.isEmpty()) {
            mutableState.update { it.copy(error = "至少保留一位要蒸馏的人物。") }
            return
        }
        val maxSentences = snapshot.maxSentences.toIntOrNull() ?: 120
        val maxChars = snapshot.maxChars.toIntOrNull() ?: 50_000
        if (maxSentences !in 20..300 || maxChars !in 2_000..200_000) {
            mutableState.update { it.copy(error = "取样句数需为 20–300，字符数需为 2000–200000。") }
            return
        }
        val segment = snapshot.selectedSegment
        val bytes = selectedBytes ?: segment?.fullText?.toByteArray(Charsets.UTF_8)
        val name = snapshot.fileName.ifBlank {
            segment?.let { "${snapshot.recommendationCharacter}-推荐片段.txt" }.orEmpty()
        }

        viewModelScope.launch {
            mutableState.update { it.copy(submitting = true, error = "") }
            try {
                val run = repository.redistill(
                    runId = runId,
                    characters = characters,
                    novelName = name,
                    novelBytes = bytes,
                    maxSentences = maxSentences,
                    maxChars = maxChars,
                )
                if (run.status == "running") {
                    distillationForeground.start()
                }
                mutableState.update { it.copy(submitting = false, completed = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val recovered = try {
                    repository.getRun(runId).takeIf { it.status == "running" }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                if (recovered != null) {
                    distillationForeground.start()
                    mutableState.update { it.copy(submitting = false, completed = true, error = "") }
                } else {
                    mutableState.update {
                        it.copy(submitting = false, error = error.message ?: "重新蒸馏失败。")
                    }
                }
            }
        }
    }

}

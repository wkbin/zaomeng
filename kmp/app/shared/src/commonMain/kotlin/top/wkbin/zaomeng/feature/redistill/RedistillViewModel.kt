package top.wkbin.zaomeng.feature.redistill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.RedistillSegmentDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.SamplingPlanDto
import top.wkbin.zaomeng.domain.distill.CharacterRedistillSuggestions
import top.wkbin.zaomeng.domain.distill.EstimateDistillSamplingUseCase
import top.wkbin.zaomeng.domain.distill.SuggestRedistillSegmentsUseCase
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
    val recommendationCharacters: Set<String> = emptySet(),
    val recommending: Boolean = false,
    val suggestions: List<CharacterRedistillSuggestions> = emptyList(),
    val selectedSegmentKeys: Set<String> = emptySet(),
    val estimatingSampling: Boolean = false,
    val samplingPlan: SamplingPlanDto? = null,
    val samplingEstimateError: String = "",
    val submitting: Boolean = false,
    val completed: Boolean = false,
    val error: String = "",
) {
    val selectedSegments: List<RedistillSelectedSegment>
        get() = suggestions.flatMap { group ->
            group.suggestions.segments.mapNotNull { segment ->
                RedistillSelectedSegment(group.character, segment)
                    .takeIf { redistillSegmentKey(group.character, segment.segmentId) in selectedSegmentKeys }
            }
        }
}

data class RedistillSelectedSegment(
    val character: String,
    val segment: RedistillSegmentDto,
)

internal fun parseRedistillCharacters(value: String): List<String> = value
    .split(',', '，', '、', ';', '；', '\n')
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()

internal fun redistillSegmentKey(character: String, segmentId: String): String = "$character::$segmentId"

internal fun combineRedistillSegments(segments: List<RedistillSelectedSegment>): String = segments
    .distinctBy { it.segment.fullText.trim() }
    .joinToString("\n\n") { selected ->
        "【${selected.character}·原文推进片段】\n${selected.segment.fullText.trim()}"
    }
    .trim()

class RedistillViewModel(
    private val repository: ZaomengRepository,
    val runId: String,
    private val distillationForeground: DistillationForeground,
    private val estimateDistillSampling: EstimateDistillSamplingUseCase,
    private val suggestRedistillSegments: SuggestRedistillSegmentsUseCase,
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

    /** UI 消费完成信号后复位，避免 entry 复用/残留下一次打开立即触发返回。 */
    fun consumeCompleted() {
        mutableState.update { it.copy(completed = false) }
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
                        recommendationCharacters = characters.toSet(),
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
                selectedSegmentKeys = emptySet(),
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
        val available = parseRedistillCharacters(value).toSet()
        mutableState.update {
            val retained = it.recommendationCharacters.intersect(available)
            it.copy(
                characters = value,
                recommendationCharacters = if (retained.isEmpty()) available else retained,
                suggestions = it.suggestions.filter { group -> group.character in available },
                selectedSegmentKeys = it.selectedSegmentKeys.filterTo(mutableSetOf()) { key ->
                    available.any { character -> key.startsWith("$character::") }
                },
                samplingPlan = null,
                error = "",
            )
        }
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
    fun toggleRecommendationCharacter(value: String) {
        val fileSelected = selectedBytes != null
        mutableState.update {
            val selected = it.recommendationCharacters.toMutableSet().apply {
                if (!add(value)) remove(value)
            }
            it.copy(
                recommendationCharacters = selected,
                suggestions = emptyList(),
                selectedSegmentKeys = emptySet(),
                sourceCharCount = if (fileSelected) it.sourceCharCount else 0,
                sourceSentenceCount = if (fileSelected) it.sourceSentenceCount else 0,
                samplingPlan = null,
                error = "",
            )
        }
        scheduleSamplingEstimate()
    }

    fun recommendSegments() {
        val characters = state.value.recommendationCharacters.toList()
        if (characters.isEmpty() || state.value.recommending) return
        viewModelScope.launch {
            val fileSelected = selectedBytes != null
            mutableState.update {
                it.copy(
                    recommending = true,
                    error = "",
                    selectedSegmentKeys = emptySet(),
                    sourceCharCount = if (fileSelected) it.sourceCharCount else 0,
                    sourceSentenceCount = if (fileSelected) it.sourceSentenceCount else 0,
                    samplingPlan = null,
                )
            }
            scheduleSamplingEstimate()
            try {
                val suggestions = suggestRedistillSegments(runId, characters)
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

    fun selectSegment(character: String, segmentId: String) {
        selectedBytes = null
        mutableState.update {
            val key = redistillSegmentKey(character, segmentId)
            val selectedKeys = it.selectedSegmentKeys.toMutableSet().apply {
                if (!add(key)) remove(key)
            }
            val selectedSegments = it.suggestions.flatMap { group ->
                group.suggestions.segments.mapNotNull { segment ->
                    segment.takeIf { redistillSegmentKey(group.character, segment.segmentId) in selectedKeys }
                        ?.let { RedistillSelectedSegment(group.character, segment) }
                }
            }
            val statistics = combineRedistillSegments(selectedSegments)
                .takeIf(String::isNotBlank)
                ?.let(::textStatistics)
            it.copy(
                fileName = "",
                fileSize = 0,
                selectedSegmentKeys = selectedKeys,
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
            val characterCount = parseRedistillCharacters(current.characters)
                .size
                .coerceAtLeast(1)
            mutableState.update {
                if (requestId == samplingEstimateRequestId) {
                    it.copy(estimatingSampling = true, samplingEstimateError = "")
                } else {
                    it
                }
            }
            try {
                val plan = estimateDistillSampling(
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
        val characters = parseRedistillCharacters(snapshot.characters)
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
        val selectedSegments = snapshot.selectedSegments
        if (selectedBytes == null && selectedSegments.isNotEmpty()) {
            val coveredCharacters = selectedSegments.map(RedistillSelectedSegment::character).toSet()
            val missingCharacters = characters.filterNot { it in coveredCharacters }
            if (missingCharacters.isNotEmpty()) {
                mutableState.update {
                    it.copy(error = "使用推荐片段时，请为本轮每位人物至少选择一段；尚未选择：${missingCharacters.joinToString("、")}。")
                }
                return
            }
        }
        val combinedSegments = combineRedistillSegments(selectedSegments)
        val bytes = selectedBytes ?: combinedSegments.takeIf(String::isNotBlank)?.encodeToByteArray()
        val name = snapshot.fileName.ifBlank {
            if (selectedSegments.isEmpty()) ""
            else "${selectedSegments.map(RedistillSelectedSegment::character).distinct().size}人-推荐片段.txt"
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

package top.wkbin.zaomeng.feature.importbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.BuiltinNovelDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.SamplingPlanDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportBookUiState(
    val fileName: String = "",
    val fileSize: Long = 0,
    val packageFile: Boolean = false,
    val sourceEncoding: String = "",
    val charCount: Int = 0,
    val sentenceCount: Int = 0,
    val characters: String = "",
    val autoDistill: Boolean = true,
    val maxSentences: String = "120",
    val maxChars: String = "50000",
    val advancedSamplingVisible: Boolean = false,
    val samplingDefaultsApplied: Boolean = false,
    val estimatingSampling: Boolean = false,
    val samplingPlan: SamplingPlanDto? = null,
    val samplingEstimateError: String = "",
    val readingFile: Boolean = false,
    val submitting: Boolean = false,
    val modelConfigured: Boolean? = null,
    val modelConfigurationError: String = "",
    val builtinNovels: List<BuiltinNovelDto> = emptyList(),
    val loadingBuiltins: Boolean = false,
    val builtinError: String = "",
    val cloningBuiltinId: String = "",
    val error: String = "",
    val createdRunId: String = "",
)

class ImportBookViewModel(
    private val repository: ZaomengRepository,
    private val distillationForeground: DistillationForeground,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ImportBookUiState())
    val state: StateFlow<ImportBookUiState> = mutableState.asStateFlow()
    private var selectedBytes: ByteArray? = null
    private var selectedKind: ImportDocumentKind? = null
    private var fileLoadJob: Job? = null
    private var modelConfigurationJob: Job? = null
    private var samplingEstimateJob: Job? = null
    private var samplingEstimateRequestId = 0L

    init {
        viewModelScope.launch {
            val preferences = repository.preferences.first()
            mutableState.update {
                it.copy(
                    characters = preferences.defaultCharacters,
                    autoDistill = preferences.autoDistill,
                )
            }
        }
        refreshModelConfiguration()
    }

    fun beginFileSelection() {
        mutableState.update { it.copy(readingFile = true, error = "") }
    }

    fun cancelFileSelection() {
        mutableState.update { it.copy(readingFile = false) }
    }

    fun loadDocument(name: String, bytes: ByteArray, kind: ImportDocumentKind) {
        fileLoadJob?.cancel()
        fileLoadJob = viewModelScope.launch {
            mutableState.update { it.copy(readingFile = true, error = "") }
            try {
                selectDocument(
                    withContext(Dispatchers.Default) {
                        ImportDocumentLoader.prepareImportDocument(name, bytes, kind)
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        readingFile = false,
                        error = error.message ?: "文件读取失败。",
                    )
                }
            }
        }
    }

    private fun selectDocument(document: ImportDocument) {
        selectedBytes = document.bytes
        selectedKind = document.kind
        samplingEstimateJob?.cancel()
        mutableState.update {
            it.copy(
                fileName = document.fileName,
                fileSize = document.bytes.size.toLong(),
                packageFile = document.kind == ImportDocumentKind.RunPackage,
                sourceEncoding = document.sourceEncoding,
                charCount = document.charCount,
                sentenceCount = document.sentenceCount,
                samplingDefaultsApplied = false,
                estimatingSampling = false,
                samplingPlan = null,
                samplingEstimateError = "",
                readingFile = false,
                error = "",
            )
        }
        scheduleSamplingEstimate()
    }

    fun updateCharacters(value: String) {
        mutableState.update {
            it.copy(
                characters = value,
                samplingPlan = null,
                samplingEstimateError = "",
                error = "",
            )
        }
        scheduleSamplingEstimate()
    }
    fun updateAutoDistill(value: Boolean) = mutableState.update { it.copy(autoDistill = value, error = "") }
    fun updateMaxSentences(value: String) {
        mutableState.update {
            it.copy(
                maxSentences = value.filter(Char::isDigit),
                samplingDefaultsApplied = true,
                samplingPlan = null,
                samplingEstimateError = "",
                error = "",
            )
        }
        scheduleSamplingEstimate()
    }
    fun updateMaxChars(value: String) {
        mutableState.update {
            it.copy(
                maxChars = value.filter(Char::isDigit),
                samplingDefaultsApplied = true,
                samplingPlan = null,
                samplingEstimateError = "",
                error = "",
            )
        }
        scheduleSamplingEstimate()
    }

    fun toggleAdvancedSampling() {
        mutableState.update { it.copy(advancedSamplingVisible = !it.advancedSamplingVisible) }
    }

    fun refreshSamplingEstimate() {
        scheduleSamplingEstimate()
    }

    fun refreshModelConfiguration() {
        if (modelConfigurationJob?.isActive == true) return
        modelConfigurationJob = viewModelScope.launch {
            mutableState.update { it.copy(modelConfigurationError = "") }
            try {
                val settings = repository.getModelSettings()
                mutableState.update {
                    it.copy(
                        modelConfigured = settings.configured,
                        modelConfigurationError = "",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        modelConfigured = false,
                        modelConfigurationError = error.message ?: "无法读取模型配置。",
                    )
                }
            }
        }
    }

    fun refreshBuiltinNovels() {
        if (state.value.loadingBuiltins) return
        viewModelScope.launch {
            mutableState.update { it.copy(loadingBuiltins = true, builtinError = "") }
            try {
                val novels = repository.listBuiltinNovels()
                mutableState.update {
                    it.copy(loadingBuiltins = false, builtinNovels = novels, builtinError = "")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loadingBuiltins = false,
                        builtinError = error.message ?: "无法读取内置书卷。",
                    )
                }
            }
        }
    }

    fun cloneBuiltinNovel(packageId: String) {
        val normalizedId = packageId.trim()
        if (normalizedId.isBlank() || state.value.cloningBuiltinId.isNotBlank()) return
        val builtin = state.value.builtinNovels.firstOrNull { it.packageId == normalizedId }
        viewModelScope.launch {
            mutableState.update {
                it.copy(cloningBuiltinId = normalizedId, builtinError = "", error = "")
            }
            val knownRunIds = captureKnownRunIds()
            try {
                val run = repository.cloneBuiltinNovel(normalizedId)
                mutableState.update {
                    it.copy(cloningBuiltinId = "", createdRunId = run.runId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val recovered = findRecoveredRun(knownRunIds) { run ->
                    when {
                        !builtin?.novelId.isNullOrBlank() -> run.novelId == builtin?.novelId
                        !builtin?.title.isNullOrBlank() -> run.title == builtin?.title
                        else -> false
                    }
                }
                mutableState.update { current ->
                    recovered?.let {
                        current.copy(cloningBuiltinId = "", createdRunId = it.runId)
                    } ?: current.copy(
                        cloningBuiltinId = "",
                        builtinError = error.message ?: "内置书卷导入失败。",
                    )
                }
            }
        }
    }

    fun submit() {
        val current = state.value
        val bytes = selectedBytes
        if (bytes == null || current.fileName.isBlank()) {
            mutableState.update { it.copy(error = "请先选择一个文件。") }
            return
        }
        val packageFile = selectedKind == ImportDocumentKind.RunPackage
        val characters = if (packageFile) {
            emptyList()
        } else {
            parseImportCharacters(current.characters)
        }
        val maxSentences = current.maxSentences.toIntOrNull() ?: 120
        val maxChars = current.maxChars.toIntOrNull() ?: 50_000
        if (!packageFile) {
            if (current.autoDistill && current.modelConfigured != true) {
                mutableState.update { it.copy(error = "开始蒸馏前需要先配置模型。") }
                return
            }
            if (characters.isEmpty()) {
                mutableState.update {
                    it.copy(
                        error = if (current.autoDistill) {
                            "正文蒸馏至少需要填写一个人物名。"
                        } else {
                            "导入正文至少需要填写一个人物名，供之后蒸馏使用。"
                        },
                    )
                }
                return
            }
            if (maxSentences !in 20..300 || maxChars !in 2_000..200_000) {
                mutableState.update { it.copy(error = "取样句数需为 20–300，字符数需为 2000–200000。") }
                return
            }
        }

        viewModelScope.launch {
            mutableState.update { it.copy(submitting = true, error = "") }
            val knownRunIds = captureKnownRunIds()
            try {
                val run = if (packageFile) {
                    repository.importPackage(current.fileName, bytes)
                } else {
                    repository.saveImportDefaults(current.characters, autoDistill = current.autoDistill)
                    repository.createNovel(
                        filename = current.fileName,
                        bytes = bytes,
                        characters = characters,
                        maxSentences = maxSentences,
                        maxChars = maxChars,
                        autoRun = current.autoDistill,
                    )
                }
                finishCreatedRun(run, packageFile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val recovered = findRecoveredRun(knownRunIds) { run ->
                    packageFile || (
                        run.novelSources.any { it.sourceName == current.fileName } &&
                            run.lockedCharacters.toSet() == characters.toSet()
                        )
                }
                if (recovered != null) {
                    finishCreatedRun(recovered, packageFile)
                } else {
                    mutableState.update {
                        it.copy(submitting = false, error = error.message ?: "导入失败。")
                    }
                }
            }
        }
    }

    fun consumeCreatedRun() {
        mutableState.update { it.copy(createdRunId = "") }
    }

    private fun finishCreatedRun(run: RunManifestDto, packageFile: Boolean) {
        if (!packageFile && run.status == "running") {
            distillationForeground.start()
        }
        mutableState.update { it.copy(submitting = false, createdRunId = run.runId) }
    }

    private fun scheduleSamplingEstimate() {
        val requestId = ++samplingEstimateRequestId
        val snapshot = state.value
        samplingEstimateJob?.cancel()
        if (snapshot.packageFile || snapshot.charCount <= 0 || snapshot.sentenceCount <= 0) {
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
            val characterCount = parseImportCharacters(current.characters).size.coerceAtLeast(1)
            mutableState.update {
                if (requestId == samplingEstimateRequestId) {
                    it.copy(estimatingSampling = true, samplingEstimateError = "")
                } else {
                    it
                }
            }
            try {
                val plan = repository.estimateSampling(
                    charCount = current.charCount,
                    sentenceCount = current.sentenceCount,
                    characterCount = characterCount,
                    maxSentences = maxSentences,
                    maxChars = maxChars,
                )
                if (requestId != samplingEstimateRequestId) return@launch
                var applySuggestedDefaults = false
                mutableState.update { latest ->
                    if (requestId != samplingEstimateRequestId) {
                        latest
                    } else {
                        applySuggestedDefaults = !latest.samplingDefaultsApplied
                        if (applySuggestedDefaults) {
                            latest.copy(
                                maxSentences = plan.suggestedMaxSentences.toString(),
                                maxChars = plan.suggestedMaxChars.toString(),
                                samplingDefaultsApplied = true,
                                samplingPlan = null,
                                samplingEstimateError = "",
                            )
                        } else {
                            latest.copy(
                                estimatingSampling = false,
                                samplingPlan = plan,
                                samplingEstimateError = "",
                            )
                        }
                    }
                }
                if (applySuggestedDefaults) scheduleSamplingEstimate()
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

    private suspend fun captureKnownRunIds(): Set<String>? = try {
        repository.listRuns().mapTo(mutableSetOf()) { it.runId }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private suspend fun findRecoveredRun(
        knownRunIds: Set<String>?,
        matches: (RunManifestDto) -> Boolean,
    ): RunManifestDto? {
        if (knownRunIds == null) return null
        return try {
            selectRecoveredRun(repository.listRuns(), knownRunIds, matches)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }
}

internal fun selectRecoveredRun(
    runs: List<RunManifestDto>,
    knownRunIds: Set<String>,
    matches: (RunManifestDto) -> Boolean,
): RunManifestDto? = runs
    .filter { it.runId !in knownRunIds }
    .filter(matches)
    .singleOrNull()

internal fun parseImportCharacters(value: String): List<String> = value
    .split(',', '，', '\n', ';', '；', '、')
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()

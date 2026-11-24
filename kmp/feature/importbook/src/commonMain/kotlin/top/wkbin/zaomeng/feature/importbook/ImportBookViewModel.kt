package top.wkbin.zaomeng.feature.importbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.data.ApiRequestException
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.data.AppRuntimeRepository
import top.wkbin.zaomeng.data.ModelSettingsRepository
import top.wkbin.zaomeng.data.RunRepository
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.SamplingPlanDto
import top.wkbin.zaomeng.data.api.SillyTavernPreviewDto
import top.wkbin.zaomeng.domain.distill.EstimateDistillSamplingUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportBookUiState(
    val fileName: String = "",
    val fileSize: Long = 0,
    val packageFile: Boolean = false,
    val documentKind: ImportDocumentKind? = null,
    val worldBookTargets: List<RunManifestDto> = emptyList(),
    val targetRunId: String = "",
    val sillyTavernPreview: SillyTavernPreviewDto? = null,
    val previewAvatar: ByteArray? = null,
    val previewBusy: Boolean = false,
    val previewError: String = "",
    val testText: String = "",
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
    val error: String = "",
    val createdRunId: String = "",
)

class ImportBookViewModel(
    private val appRuntime: AppRuntimeRepository,
    private val modelSettings: ModelSettingsRepository,
    private val runs: RunRepository,
    private val distillationForeground: DistillationForeground,
    private val estimateDistillSampling: EstimateDistillSamplingUseCase,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ImportBookUiState())
    val state: StateFlow<ImportBookUiState> = mutableState.asStateFlow()
    private var selectedBytes: ByteArray? = null
    private var selectedKind: ImportDocumentKind? = null
    private var fileLoadJob: Job? = null
    private var previewJob: Job? = null
    private var modelConfigurationJob: Job? = null
    private var samplingEstimateJob: Job? = null
    private var samplingEstimateRequestId = 0L

    init {
        viewModelScope.launch {
            val preferences = appRuntime.preferences.first()
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

    fun clearDocument() {
        if (state.value.readingFile || state.value.submitting) return
        fileLoadJob?.cancel()
        previewJob?.cancel()
        samplingEstimateJob?.cancel()
        selectedBytes = null
        selectedKind = null
        mutableState.update { current ->
            ImportBookUiState(
                characters = current.characters,
                autoDistill = current.autoDistill,
                maxSentences = current.maxSentences,
                maxChars = current.maxChars,
                advancedSamplingVisible = current.advancedSamplingVisible,
                modelConfigured = current.modelConfigured,
                modelConfigurationError = current.modelConfigurationError,
                createdRunId = current.createdRunId,
            )
        }
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
                if (kind == ImportDocumentKind.WorldBook) {
                    val targets = runs.listRuns()
                    mutableState.update {
                        it.copy(
                            worldBookTargets = targets,
                            readingFile = false,
                            error = if (targets.isEmpty()) "请先导入角色卡或创建一个书卷。" else "",
                        )
                    }
                }
                if (kind == ImportDocumentKind.WorldBook || kind == ImportDocumentKind.CharacterCard) {
                    refreshSillyTavernPreview()
                }
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
        previewJob?.cancel()
        samplingEstimateJob?.cancel()
        mutableState.update {
            it.copy(
                fileName = document.fileName,
                fileSize = document.bytes.size.toLong(),
                packageFile = document.kind != ImportDocumentKind.NovelText,
                documentKind = document.kind,
                worldBookTargets = emptyList(),
                targetRunId = "",
                sillyTavernPreview = null,
                previewAvatar = null,
                previewBusy = false,
                previewError = "",
                testText = "",
                sourceEncoding = document.sourceEncoding,
                charCount = document.charCount,
                sentenceCount = document.sentenceCount,
                samplingDefaultsApplied = false,
                estimatingSampling = false,
                samplingPlan = null,
                samplingEstimateError = "",
                readingFile = document.kind == ImportDocumentKind.WorldBook,
                error = "",
            )
        }
        scheduleSamplingEstimate()
    }

    fun selectWorldBookTarget(runId: String) {
        if (state.value.targetRunId == runId) return
        mutableState.update { it.copy(targetRunId = runId, sillyTavernPreview = null, error = "") }
        refreshSillyTavernPreview(test = state.value.testText.isNotBlank())
    }

    fun updateTestText(value: String) {
        mutableState.update {
            it.copy(testText = value.take(2000), sillyTavernPreview = it.sillyTavernPreview?.copy(testedEntries = null))
        }
    }

    fun refreshSillyTavernPreview(test: Boolean = false) {
        val bytes = selectedBytes ?: return
        val snapshot = state.value
        val worldBook = selectedKind == ImportDocumentKind.WorldBook
        if (!worldBook && selectedKind != ImportDocumentKind.CharacterCard) return
        previewJob?.cancel()
        mutableState.update { it.copy(previewBusy = true, previewError = "") }
        previewJob = viewModelScope.launch {
            try {
                val result = runs.previewSillyTavern(
                    snapshot.fileName,
                    bytes,
                    worldBook,
                    snapshot.targetRunId.ifBlank { null },
                    snapshot.testText.takeIf { test && it.isNotBlank() },
                )
                ensureActive()
                mutableState.update {
                    it.copy(
                        sillyTavernPreview = if (it.testText == snapshot.testText) result else result.copy(testedEntries = null),
                        previewAvatar = bytes.takeIf { result.hasAvatar },
                        previewBusy = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(previewBusy = false, previewError = error.message ?: "无法预览文件。") }
            }
        }
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
                val settings = modelSettings.getModelSettings()
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

    fun submit() {
        val current = state.value
        if (current.submitting || current.readingFile) return
        val bytes = selectedBytes
        if (bytes == null || current.fileName.isBlank()) {
            mutableState.update { it.copy(error = "请先选择一个文件。") }
            return
        }
        val kind = selectedKind ?: return
        if ((kind == ImportDocumentKind.CharacterCard || kind == ImportDocumentKind.WorldBook) &&
            (current.previewBusy || current.sillyTavernPreview == null)
        ) return
        val packageFile = kind != ImportDocumentKind.NovelText
        val targetRunId = if (kind == ImportDocumentKind.WorldBook) {
            current.targetRunId.takeIf(String::isNotBlank) ?: return
        } else null
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

        mutableState.update { it.copy(submitting = true, error = "") }
        viewModelScope.launch {
            val canRecover = kind == ImportDocumentKind.RunPackage || kind == ImportDocumentKind.NovelText
            val knownRunIds = if (canRecover) captureKnownRunIds() else null
            try {
                val run = if (packageFile) {
                    runs.importPackage(
                        current.fileName,
                        bytes,
                        targetRunId = targetRunId,
                        expectedWorldBookRevision = current.sillyTavernPreview?.previousBookRevision,
                    )
                } else {
                    runs.saveImportDefaults(current.characters, autoDistill = current.autoDistill)
                    runs.createNovel(
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
                if (targetRunId != null && error is ApiRequestException && error.statusCode == 409) {
                    mutableState.update {
                        it.copy(
                            submitting = false,
                            sillyTavernPreview = null,
                            error = "目标书卷的独立世界书已变化，请查看刷新后的预览再确认。",
                        )
                    }
                    refreshSillyTavernPreview(test = current.testText.isNotBlank())
                    return@launch
                }
                val recovered = if (canRecover) findRecoveredRun(knownRunIds) { run ->
                    packageFile || (
                        run.novelSources.any { it.sourceName == current.fileName } &&
                            run.lockedCharacters.toSet() == characters.toSet()
                        )
                } else null
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
        clearDocument()
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
                val plan = estimateDistillSampling(
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
        runs.listRuns().mapTo(mutableSetOf()) { it.runId }
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
            selectRecoveredRun(runs.listRuns(), knownRunIds, matches)
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

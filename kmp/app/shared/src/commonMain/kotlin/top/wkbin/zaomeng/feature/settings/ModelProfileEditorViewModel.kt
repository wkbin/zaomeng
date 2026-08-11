package top.wkbin.zaomeng.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.ModelProfileDto
import top.wkbin.zaomeng.data.api.ModelCapabilityReportDto
import top.wkbin.zaomeng.data.api.SaveModelSettingsRequest
import top.wkbin.zaomeng.data.api.TestModelSettingsRequest

data class ModelProfileEditorUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val testing: Boolean = false,
    val detecting: Boolean = false,
    val deleting: Boolean = false,
    val isNew: Boolean = true,
    val profileId: String = "",
    val activeProfileId: String = "",
    val profileCount: Int = 0,
    val profileName: String = "新模型",
    val selectedCatalogId: String = "",
    val provider: String = "openai-compatible",
    val model: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val apiKeyConfigured: Boolean = false,
    val maxTokens: String = "0",
    val reasoningEffort: String = "off",
    val tokenParameter: String = "auto",
    val responseFormatMode: String = "auto",
    val capabilityReport: ModelCapabilityReportDto? = null,
    val message: String = "",
    val error: String = "",
    val completed: Boolean = false,
    val original: EditorSnapshot? = null,
) {
    val isDirty: Boolean
        get() = original?.let {
            profileName != it.profileName || selectedCatalogId != it.selectedCatalogId || provider != it.provider ||
                model != it.model || baseUrl != it.baseUrl || maxTokens != it.maxTokens ||
                reasoningEffort != it.reasoningEffort || tokenParameter != it.tokenParameter ||
                responseFormatMode != it.responseFormatMode || apiKey.isNotBlank()
        } ?: false
}

data class EditorSnapshot(
    val profileName: String,
    val selectedCatalogId: String,
    val provider: String,
    val model: String,
    val baseUrl: String,
    val maxTokens: String,
    val reasoningEffort: String,
    val tokenParameter: String,
    val responseFormatMode: String,
)

class ModelProfileEditorViewModel(
    private val repository: ZaomengRepository,
    private val requestedProfileId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ModelProfileEditorUiState())
    val state: StateFlow<ModelProfileEditorUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun selectCatalog(catalog: ModelCatalog) = update {
        val selectedModel = catalog.models.firstOrNull()?.id.orEmpty()
        copy(
            selectedCatalogId = catalog.id,
            provider = catalog.provider,
            model = selectedModel,
            baseUrl = catalog.baseUrl,
            reasoningEffort = normalizedReasoningEffort(
                catalog.provider,
                catalog.baseUrl,
                selectedModel,
                reasoningEffort,
            ),
        )
    }

    fun updateProfileName(value: String) = update { copy(profileName = value) }
    fun updateProvider(value: String) = update {
        copy(
            provider = value,
            selectedCatalogId = "custom",
            reasoningEffort = normalizedReasoningEffort(value, baseUrl, model, reasoningEffort),
        )
    }
    fun updateModel(value: String) = update {
        copy(
            model = value,
            reasoningEffort = normalizedReasoningEffort(provider, baseUrl, value, reasoningEffort),
        )
    }
    fun updateBaseUrl(value: String) = update {
        copy(
            baseUrl = value,
            selectedCatalogId = "custom",
            reasoningEffort = normalizedReasoningEffort(provider, value, model, reasoningEffort),
        )
    }
    fun updateApiKey(value: String) = update { copy(apiKey = value) }
    fun updateMaxTokens(value: String) = update { copy(maxTokens = value.filter(Char::isDigit).take(5)) }
    fun updateReasoningEffort(value: String) = update { copy(reasoningEffort = value) }
    fun updateTokenParameter(value: String) = update { copy(tokenParameter = value) }

    fun testConnection() {
        val request = validatedRequest() ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(testing = true, error = "", message = "") }
            try {
                val tested = repository.testModelSettings(
                    TestModelSettingsRequest(
                        provider = request.provider,
                        model = request.model,
                        baseUrl = request.baseUrl,
                        apiKey = request.apiKey,
                        maxTokens = request.maxTokens,
                        reasoningEffort = request.reasoningEffort,
                        tokenParameter = request.tokenParameter,
                        responseFormatMode = request.responseFormatMode,
                        profileId = request.profileId,
                    ),
                )
                mutableState.update {
                    it.copy(testing = false, message = "连接成功：${tested.provider} / ${tested.model}（${tested.latencyMs} ms）")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(testing = false, error = error.message ?: "模型连接失败。") }
            }
        }
    }

    fun detectCapabilities() {
        val request = validatedRequest() ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(detecting = true, capabilityReport = null, error = "", message = "") }
            try {
                val report = repository.detectModelCapabilities(
                    TestModelSettingsRequest(
                        provider = request.provider,
                        model = request.model,
                        baseUrl = request.baseUrl,
                        apiKey = request.apiKey,
                        maxTokens = request.maxTokens,
                        reasoningEffort = request.reasoningEffort,
                        tokenParameter = request.tokenParameter,
                        responseFormatMode = request.responseFormatMode,
                        profileId = request.profileId,
                    ),
                )
                mutableState.update {
                    it.copy(
                        detecting = false,
                        capabilityReport = report,
                        maxTokens = report.recommendedMaxTokens.toString(),
                        reasoningEffort = normalizedReasoningEffort(
                            it.provider,
                            it.baseUrl,
                            it.model,
                            report.recommendedReasoningEffort,
                        ),
                        tokenParameter = report.recommendedTokenParameter,
                        responseFormatMode = report.recommendedResponseFormatMode,
                        message = if (report.ok) {
                            "模型能力检测完成，推荐配置已填入，保存后生效。"
                        } else {
                            "检测完成，但接口未通过核心能力探测，请先检查警告。"
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(detecting = false, error = error.message ?: "模型能力检测失败。") }
            }
        }
    }

    fun applyCapabilityRecommendation() {
        mutableState.update { current ->
            val report = current.capabilityReport ?: return@update current
            current.copy(
                maxTokens = report.recommendedMaxTokens.toString(),
                reasoningEffort = normalizedReasoningEffort(
                    current.provider,
                    current.baseUrl,
                    current.model,
                    report.recommendedReasoningEffort,
                ),
                tokenParameter = report.recommendedTokenParameter,
                responseFormatMode = report.recommendedResponseFormatMode,
                error = "",
                message = "已应用检测建议，保存后生效。",
            )
        }
    }

    fun save() {
        val request = validatedRequest() ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, error = "", message = "") }
            try {
                repository.saveModelSettings(request.copy(activateProfile = state.value.isNew))
                mutableState.update { it.copy(saving = false, completed = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(saving = false, error = error.message ?: "模型配置保存失败。") }
            }
        }
    }

    fun delete() {
        val current = state.value
        if (current.isNew || current.profileCount <= 1 || current.deleting) return
        viewModelScope.launch {
            mutableState.update { it.copy(deleting = true, error = "", message = "") }
            try {
                repository.deleteModelProfile(current.profileId)
                mutableState.update { it.copy(deleting = false, completed = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(deleting = false, error = error.message ?: "删除模型档案失败。") }
            }
        }
    }

    /** UI 消费完成信号后复位，避免 entry 复用/残留下一次打开立即触发返回。 */
    fun consumeCompleted() {
        mutableState.update { it.copy(completed = false) }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val settings = repository.getModelSettings()
                val profile = settings.profiles.firstOrNull { it.profileId == requestedProfileId }
                if (requestedProfileId.isNotBlank() && profile == null) {
                    mutableState.value = ModelProfileEditorUiState(loading = false, error = "模型档案不存在。")
                    return@launch
                }
                val initial = profile?.toEditorState(settings.activeProfileId, settings.profiles.size)
                    ?: ModelProfileEditorUiState(loading = false, profileCount = settings.profiles.size)
                mutableState.value = initial.copy(original = initial.snapshot())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.value = ModelProfileEditorUiState(loading = false, error = error.message ?: "模型档案读取失败。")
            }
        }
    }

    private fun validatedRequest(): SaveModelSettingsRequest? {
        val current = state.value
        val maxTokens = current.maxTokens.toIntOrNull() ?: 0
        val error = when {
            current.profileName.trim().isBlank() -> "请填写档案名称。"
            current.model.trim().isBlank() -> "请填写或选择模型名称。"
            maxTokens !in 0..16000 -> "最大输出 Token 需要在 0 到 16000 之间。"
            else -> ""
        }
        if (error.isNotBlank()) {
            mutableState.update { it.copy(error = error, message = "") }
            return null
        }
        return SaveModelSettingsRequest(
            provider = current.provider,
            model = current.model.trim(),
            baseUrl = current.baseUrl.trim(),
            apiKey = current.apiKey.trim(),
            maxTokens = maxTokens,
            reasoningEffort = normalizedReasoningEffort(
                current.provider,
                current.baseUrl,
                current.model,
                current.reasoningEffort,
            ),
            tokenParameter = current.tokenParameter,
            responseFormatMode = current.responseFormatMode,
            profileId = current.profileId,
            profileName = current.profileName.trim(),
            createProfile = current.isNew,
        )
    }

    private inline fun update(transform: ModelProfileEditorUiState.() -> ModelProfileEditorUiState) {
        mutableState.update { it.transform().copy(error = "", message = "") }
    }
}

private fun ModelProfileDto.toEditorState(activeProfileId: String, profileCount: Int): ModelProfileEditorUiState =
    ModelProfileEditorUiState(
        loading = false,
        isNew = false,
        profileId = profileId,
        activeProfileId = activeProfileId,
        profileCount = profileCount,
        profileName = name.ifBlank { model },
        selectedCatalogId = editorCatalogFor(provider, baseUrl, model)?.id ?: "custom",
        provider = provider,
        model = model,
        baseUrl = baseUrl,
        apiKeyConfigured = apiKeyConfigured,
        maxTokens = maxTokens.toString(),
        reasoningEffort = normalizedReasoningEffort(provider, baseUrl, model, reasoningEffort),
        tokenParameter = tokenParameter,
        responseFormatMode = responseFormatMode,
    )

private fun ModelProfileEditorUiState.snapshot() = EditorSnapshot(
    profileName,
    selectedCatalogId,
    provider,
    model,
    baseUrl,
    maxTokens,
    reasoningEffort,
    tokenParameter,
    responseFormatMode,
)

private fun editorCatalogFor(provider: String, baseUrl: String, model: String): ModelCatalog? =
    modelCatalogs.firstOrNull { catalog ->
        catalog.models.any { it.id == model } && catalog.provider == provider &&
            catalog.baseUrl.trimEnd('/') == baseUrl.trimEnd('/')
    }

internal fun modelReasoningEfforts(
    provider: String,
    baseUrl: String,
    model: String,
): List<String> {
    val normalizedModel = model.trim().lowercase()
    if (
        provider == "openai-compatible" &&
        normalizedModel.startsWith("step-")
    ) {
        return if (normalizedModel == "step-3.5-flash-2603") {
            listOf("auto", "low", "high")
        } else {
            listOf("auto", "low", "medium", "high")
        }
    }
    if (normalizedModel.startsWith("deepseek-v4-")) {
        return if (baseUrl.contains("api.deepseek.com", ignoreCase = true)) {
            listOf("auto", "off", "low", "medium", "high", "xhigh")
        } else {
            listOf("auto", "low", "medium", "high")
        }
    }
    if (provider in setOf("openai", "openai-compatible")) {
        if (normalizedModel.startsWith("gpt-5")) {
            return if (provider == "openai") {
                listOf("auto", "off", "low", "medium", "high", "xhigh")
            } else {
                listOf("auto", "low", "medium", "high")
            }
        }
        if (listOf("o1", "o3", "o4").any(normalizedModel::startsWith)) {
            return listOf("auto", "low", "medium", "high")
        }
    }
    if (
        provider == "openai-compatible" &&
        baseUrl.contains("dashscope.aliyuncs.com", ignoreCase = true) &&
        normalizedModel.startsWith("qwen")
    ) {
        return listOf("auto", "off")
    }
    return listOf("auto")
}

internal fun normalizedReasoningEffort(
    provider: String,
    baseUrl: String,
    model: String,
    current: String,
): String {
    val supported = modelReasoningEfforts(provider, baseUrl, model)
    return current.takeIf(supported::contains)
        ?: "off".takeIf(supported::contains)
        ?: "auto"
}

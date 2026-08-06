package top.wkbin.zaomeng.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.ModelProfileDto
import top.wkbin.zaomeng.data.api.ModelSettingsDto
import top.wkbin.zaomeng.data.api.SaveModelSettingsRequest
import top.wkbin.zaomeng.data.api.TestModelSettingsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelChoice(
    val id: String,
    val title: String,
)

data class ModelCatalog(
    val id: String,
    val title: String,
    val provider: String,
    val baseUrl: String,
    val models: List<ModelChoice>,
    val needsApiKey: Boolean = true,
)

val modelCatalogs = listOf(
    ModelCatalog(
        "deepseek", "DeepSeek", "openai-compatible", "https://api.deepseek.com",
        listOf(
            ModelChoice("deepseek-v4-flash", "DeepSeek V4 Flash"),
            ModelChoice("deepseek-v4-pro", "DeepSeek V4 Pro")
        ),
    ),
    ModelCatalog(
        "openai", "OpenAI", "openai", "",
        listOf(
            ModelChoice("gpt-5", "GPT-5"),
            ModelChoice("gpt-5-mini", "GPT-5 mini"),
            ModelChoice("gpt-4.1", "GPT-4.1")
        ),
    ),
    ModelCatalog(
        "qwen",
        "通义千问",
        "openai-compatible",
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        listOf(
            ModelChoice("qwen-plus", "Qwen Plus"),
            ModelChoice("qwen-max", "Qwen Max"),
            ModelChoice("qwen-turbo", "Qwen Turbo")
        ),
    ),
    ModelCatalog(
        "mimo", "小米 MiMo", "openai-compatible", "https://api.xiaomimimo.com/v1",
        listOf(
            ModelChoice("mimo-v2.5", "MiMo V2.5"),
            ModelChoice("mimo-v2.5-pro", "MiMo V2.5 Pro")
        ),
    ),
    ModelCatalog(
        "stepfun", "阶跃星辰", "openai-compatible", "https://api.stepfun.com/v1",
        listOf(
            ModelChoice("step-3.7-flash", "Step 3.7 Flash"),
            ModelChoice("step-3.5-flash", "Step 3.5 Flash")
        ),
    ),
    ModelCatalog(
        "anthropic", "Anthropic", "anthropic", "",
        listOf(
            ModelChoice("claude-sonnet-4-20250514", "Claude Sonnet"),
            ModelChoice("claude-3-7-sonnet-20250219", "Claude 3.7 Sonnet")
        ),
    ),
    ModelCatalog(
        "ollama", "Ollama", "ollama", "http://127.0.0.1:11434",
        listOf(
            ModelChoice("qwen2.5:7b", "Qwen 2.5 7B"),
            ModelChoice("llama3.3", "Llama 3.3"),
            ModelChoice("deepseek-r1:7b", "DeepSeek R1 7B")
        ),
        needsApiKey = false,
    ),
    ModelCatalog("custom", "自定义", "openai-compatible", "", emptyList()),
)

data class SettingsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val testing: Boolean = false,
    val exportingDiagnostics: Boolean = false,
    val switching: Boolean = false,
    val provider: String = "openai-compatible",
    val model: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val profileName: String = "",
    val selectedProfileId: String = "",
    val selectedCatalogId: String = "",
    val activeProfileId: String = "",
    val creatingProfile: Boolean = false,
    val profiles: List<ModelProfileDto> = emptyList(),
    val apiKeyConfigured: Boolean = false,
    val configured: Boolean = false,
    val message: String = "",
    val error: String = "",
)

class SettingsViewModel(
    private val repository: ZaomengRepository,
    context: Context,
) : ViewModel() {
    private val applicationContext = context.applicationContext
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() = launchRequest(
        before = { it.copy(loading = true, error = "") },
        onSuccess = { settings -> applySettings(settings, loading = false) },
        failure = "模型配置读取失败。",
    ) { repository.getModelSettings() }

    fun selectProfile(profile: ModelProfileDto) {
        mutableState.update {
            it.copy(
                selectedProfileId = profile.profileId,
                selectedCatalogId = catalogFor(
                    profile.provider,
                    profile.baseUrl,
                    profile.model
                )?.id.orEmpty(),
                profileName = profile.name,
                provider = profile.provider,
                model = profile.model,
                baseUrl = profile.baseUrl,
                apiKey = "",
                apiKeyConfigured = profile.apiKeyConfigured,
                configured = profile.configured,
                creatingProfile = false,
                error = "",
                message = "",
            )
        }
    }

    fun beginCreateProfile() {
        mutableState.update {
            it.copy(
                selectedProfileId = "",
                selectedCatalogId = "",
                profileName = "新模型",
                model = "",
                baseUrl = "",
                apiKey = "",
                apiKeyConfigured = false,
                configured = false,
                creatingProfile = true,
                error = "",
                message = "",
            )
        }
    }

    fun selectModelCatalog(catalog: ModelCatalog) {
        val firstModel = catalog.models.firstOrNull()?.id.orEmpty()
        mutableState.update {
            it.copy(
                profileName = catalog.title,
                selectedProfileId = "",
                selectedCatalogId = catalog.id,
                provider = catalog.provider,
                model = firstModel,
                baseUrl = catalog.baseUrl,
                apiKey = "",
                apiKeyConfigured = false,
                creatingProfile = true,
                error = "",
                message = if (catalog.needsApiKey) "请选择模型，输入 API Key 后保存。" else "请选择本机已安装的模型，确认 Ollama 正在运行后保存。",
            )
        }
    }

    fun selectCatalogModel(modelId: String) =
        mutableState.update { it.copy(model = modelId, error = "", message = "") }

    fun activateSelectedProfile() {
        val profileId = state.value.selectedProfileId
        if (profileId.isBlank() || profileId == state.value.activeProfileId) return
        launchRequest(
            before = { it.copy(switching = true, error = "", message = "") },
            onSuccess = { settings ->
                applySettings(settings, selectedProfileId = profileId, message = "已切换当前模型。")
            },
            failure = "模型切换失败。",
            afterFailure = { it.copy(switching = false) },
        ) { repository.activateModelProfile(profileId) }
    }

    fun updateProfileName(value: String) = update { copy(profileName = value) }
    fun updateProvider(value: String) = update { copy(provider = value, selectedCatalogId = "") }
    fun updateModel(value: String) = update { copy(model = value) }
    fun updateBaseUrl(value: String) = update { copy(baseUrl = value, selectedCatalogId = "") }
    fun updateApiKey(value: String) = update { copy(apiKey = value) }

    fun save() {
        val current = state.value
        if (current.profileName.isBlank()) {
            mutableState.update { it.copy(error = "请填写配置名称。", message = "") }
            return
        }
        if (current.model.isBlank()) {
            mutableState.update { it.copy(error = "请填写模型名称。", message = "") }
            return
        }
        launchRequest(
            before = { it.copy(saving = true, error = "", message = "") },
            onSuccess = { settings ->
                applySettings(
                    settings,
                    message = "模型配置已保存在这台手机中。"
                )
            },
            failure = "模型配置保存失败。",
            afterFailure = { it.copy(saving = false) },
        ) {
            repository.saveModelSettings(
                SaveModelSettingsRequest(
                    provider = current.provider,
                    model = current.model.trim(),
                    baseUrl = current.baseUrl.trim(),
                    apiKey = current.apiKey.trim(),
                    maxTokens = 0,
                    profileId = current.selectedProfileId,
                    profileName = current.profileName.trim(),
                    createProfile = current.creatingProfile,
                ),
            )
        }
    }

    fun testConnection() {
        val current = state.value
        if (current.model.isBlank()) {
            mutableState.update { it.copy(error = "请先选择或填写模型。", message = "") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(testing = true, error = "", message = "") }
            try {
                val tested = repository.testModelSettings(
                    TestModelSettingsRequest(
                        provider = current.provider,
                        model = current.model.trim(),
                        baseUrl = current.baseUrl.trim(),
                        apiKey = current.apiKey.trim(),
                        maxTokens = 0,
                        profileId = current.selectedProfileId,
                    ),
                )
                mutableState.update {
                    it.copy(
                        testing = false,
                        message = "连接成功：${tested.provider} / ${tested.model}（${tested.latencyMs} ms）",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(testing = false, error = error.message ?: "模型连接失败。")
                }
            }
        }
    }

    fun deleteSelectedProfile() {
        val current = state.value
        if (current.selectedProfileId.isBlank()) return
        if (current.profiles.size <= 1) {
            mutableState.update { it.copy(error = "至少保留一个模型配置。", message = "") }
            return
        }
        launchRequest(
            before = { it.copy(saving = true, error = "", message = "") },
            onSuccess = { settings -> applySettings(settings, message = "模型配置已删除。") },
            failure = "删除模型配置失败。",
            afterFailure = { it.copy(saving = false) },
        ) { repository.deleteModelProfile(current.selectedProfileId) }
    }

    fun exportDiagnostics(uri: Uri) {
        if (state.value.exportingDiagnostics) return
        viewModelScope.launch {
            mutableState.update { it.copy(exportingDiagnostics = true, error = "", message = "") }
            try {
                applicationContext.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { output ->
                    repository.exportDiagnostics(output)
                } ?: error("无法写入所选位置。")
                mutableState.update {
                    it.copy(exportingDiagnostics = false, message = "脱敏诊断信息已导出。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(exportingDiagnostics = false, error = error.message ?: "诊断信息导出失败。")
                }
            }
        }
    }

    private fun applySettings(
        settings: ModelSettingsDto,
        loading: Boolean = false,
        selectedProfileId: String = settings.activeProfileId,
        message: String = "",
    ) {
        val selected = settings.profiles.firstOrNull { it.profileId == selectedProfileId }
            ?: settings.profiles.firstOrNull { it.profileId == settings.activeProfileId }
        mutableState.value = SettingsUiState(
            loading = loading,
            provider = selected?.provider ?: settings.provider.ifBlank { "openai-compatible" },
            model = selected?.model ?: settings.model,
            baseUrl = selected?.baseUrl ?: settings.baseUrl,
            profileName = selected?.name ?: settings.model,
            selectedProfileId = selected?.profileId.orEmpty(),
            selectedCatalogId = selected?.let { catalogFor(it.provider, it.baseUrl, it.model)?.id }
                .orEmpty(),
            activeProfileId = settings.activeProfileId,
            profiles = settings.profiles,
            apiKeyConfigured = selected?.apiKeyConfigured ?: settings.apiKeyConfigured,
            configured = selected?.configured ?: settings.configured,
            message = message,
        )
    }

    private fun launchRequest(
        before: (SettingsUiState) -> SettingsUiState,
        onSuccess: (ModelSettingsDto) -> Unit,
        failure: String,
        afterFailure: (SettingsUiState) -> SettingsUiState = { it.copy(loading = false) },
        block: suspend () -> ModelSettingsDto,
    ) {
        viewModelScope.launch {
            mutableState.update(before)
            try {
                onSuccess(block())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { afterFailure(it).copy(error = error.message ?: failure) }
            }
        }
    }

    private inline fun update(transform: SettingsUiState.() -> SettingsUiState) {
        mutableState.update { it.transform().copy(error = "", message = "") }
    }

    private fun catalogFor(provider: String, baseUrl: String, model: String): ModelCatalog? =
        modelCatalogs.firstOrNull { catalog ->
            catalog.models.any { it.id == model } &&
                    catalog.provider == provider &&
                    catalog.baseUrl.trimEnd('/') == baseUrl.trimEnd('/')
        }
}

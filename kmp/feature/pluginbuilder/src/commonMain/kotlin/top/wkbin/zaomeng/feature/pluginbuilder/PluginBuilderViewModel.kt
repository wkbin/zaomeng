package top.wkbin.zaomeng.feature.pluginbuilder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okio.Sink
import top.wkbin.zaomeng.data.PluginRepository
import top.wkbin.zaomeng.data.api.PluginBuilderActionMode
import top.wkbin.zaomeng.data.api.PluginBuilderSettingDraft
import top.wkbin.zaomeng.data.api.PluginBuilderSettingType
import top.wkbin.zaomeng.data.api.PluginBuilderTemplate
import top.wkbin.zaomeng.data.api.PluginBuilderValidationDto
import top.wkbin.zaomeng.data.api.PluginDraft
import top.wkbin.zaomeng.data.api.suggestPluginId
import top.wkbin.zaomeng.data.api.suggestPluginSettingKey

data class PluginBuilderUiState(
    val draft: PluginDraft = PluginDraft(prompt = defaultPrompt(PluginBuilderTemplate.ChatAction)),
    val validation: PluginBuilderValidationDto? = null,
    val validating: Boolean = false,
    val working: Boolean = false,
    val message: String = "",
    val error: String = "",
    val exportRequestId: Int = 0,
    val exportDestinationPending: Boolean = false,
    val pendingExportDraft: PluginDraft? = null,
    val pendingExportFilename: String = "",
)

class PluginBuilderViewModel(
    private val repository: PluginRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PluginBuilderUiState())
    val state: StateFlow<PluginBuilderUiState> = mutableState.asStateFlow()
    private var validationJob: Job? = null

    init {
        scheduleValidation(immediate = true)
    }

    fun updateName(value: String) = updateDraft { current ->
        val titleTracksName = current.title.isBlank() || current.title == current.name
        current.copy(
            name = value,
            id = suggestPluginId(value),
            title = if (titleTracksName) value else current.title,
        )
    }

    fun updateVersion(value: String) = updateDraft { it.copy(version = value) }
    fun updateDescription(value: String) = updateDraft { it.copy(description = value) }
    fun updateTitle(value: String) = updateDraft { it.copy(title = value) }
    fun updatePrompt(value: String) = updateDraft { it.copy(prompt = value) }
    fun updateActionMode(value: PluginBuilderActionMode) = updateDraft { it.copy(actionMode = value) }

    fun updateTemplate(template: PluginBuilderTemplate) = updateDraft { current ->
        val knownPrompts = PluginBuilderTemplate.entries.map(::defaultPrompt).toSet()
        current.copy(
            template = template,
            prompt = if (current.prompt.isBlank() || current.prompt in knownPrompts) defaultPrompt(template) else current.prompt,
        )
    }

    fun insertVariable(variable: String) = updateDraft { current ->
        val separator = if (current.prompt.isBlank() || current.prompt.last().isWhitespace()) "" else " "
        current.copy(prompt = current.prompt + separator + variable)
    }

    fun addSetting() = updateDraft { current ->
        val index = current.settings.size + 1
        val title = if (current.settings.isEmpty()) "语气" else "选项 $index"
        val key = if (current.settings.isEmpty()) "tone" else "option_$index"
        current.copy(
            settings = current.settings + PluginBuilderSettingDraft(
                key = key,
                title = title,
                type = PluginBuilderSettingType.Enum,
                defaultValue = "默认",
                options = listOf("默认", "增强"),
            ),
        )
    }

    fun removeSetting(index: Int) = updateSettings(index) { null }

    fun updateSettingTitle(index: Int, value: String) = updateSettings(index) { setting ->
        setting.copy(title = value, key = suggestPluginSettingKey(value))
    }

    fun updateSettingType(index: Int, type: PluginBuilderSettingType) = updateSettings(index) { setting ->
        when (type) {
            PluginBuilderSettingType.Boolean -> setting.copy(type = type, defaultValue = "false", options = emptyList())
            PluginBuilderSettingType.Integer -> setting.copy(type = type, defaultValue = "1", options = emptyList())
            PluginBuilderSettingType.Enum -> setting.copy(type = type, defaultValue = "默认", options = listOf("默认", "增强"))
        }
    }

    fun updateSettingDefault(index: Int, value: String) = updateSettings(index) { it.copy(defaultValue = value) }

    fun updateSettingOptions(index: Int, value: String) = updateSettings(index) { setting ->
        val options = value.split(',', '，', '\n').map(String::trim).filter(String::isNotBlank).distinct()
        setting.copy(options = options)
    }

    fun installForTesting() {
        if (state.value.working || state.value.validating) return
        viewModelScope.launch {
            mutableState.update { it.copy(working = true, message = "", error = "") }
            try {
                val installed = repository.installPluginDraft(state.value.draft)
                mutableState.update {
                    it.copy(
                        working = false,
                        message = "已安装「${installed.name}」，默认保持停用；可返回插件页启用后试用。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(working = false, error = error.message ?: "安装插件失败。") }
            }
        }
    }

    fun prepareExport() {
        if (state.value.working || state.value.validating) return
        viewModelScope.launch {
            mutableState.update { it.copy(working = true, message = "", error = "") }
            try {
                val validation = repository.validatePluginDraft(state.value.draft)
                if (!validation.valid) {
                    mutableState.update {
                        it.copy(
                            working = false,
                            validation = validation,
                            draft = validation.draft,
                            error = "请先修正校验问题，再导出分享。",
                        )
                    }
                    return@launch
                }
                mutableState.update {
                    it.copy(
                        working = false,
                        validation = validation,
                        draft = validation.draft,
                        exportRequestId = it.exportRequestId + 1,
                        exportDestinationPending = true,
                        pendingExportDraft = validation.draft,
                        pendingExportFilename = validation.filename,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(working = false, error = error.message ?: "准备导出失败。") }
            }
        }
    }

    fun consumeExportDestinationRequest(requestId: Int) {
        if (state.value.exportRequestId == requestId) {
            mutableState.update { it.copy(exportDestinationPending = false) }
        }
    }

    suspend fun savePendingExport(destination: Sink) {
        val draft = state.value.pendingExportDraft ?: return
        try {
            val bytes = repository.exportPluginDraft(draft, destination)
            mutableState.update {
                it.copy(
                    pendingExportDraft = null,
                    pendingExportFilename = "",
                    message = "插件包已导出（${formatBytes(bytes)}）。",
                    error = "",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(error = error.message ?: "导出插件失败。") }
        }
    }

    fun cancelExportDestination() {
        mutableState.update {
            it.copy(pendingExportDraft = null, pendingExportFilename = "", exportDestinationPending = false)
        }
    }

    private fun updateDraft(transform: (PluginDraft) -> PluginDraft) {
        mutableState.update { it.copy(draft = transform(it.draft), message = "", error = "") }
        scheduleValidation()
    }

    private fun updateSettings(index: Int, transform: (PluginBuilderSettingDraft) -> PluginBuilderSettingDraft?) {
        updateDraft { draft ->
            if (index !in draft.settings.indices) return@updateDraft draft
            val updated = draft.settings.toMutableList()
            val item = transform(updated[index])
            if (item == null) updated.removeAt(index) else updated[index] = item
            draft.copy(settings = updated)
        }
    }

    private fun scheduleValidation(immediate: Boolean = false) {
        validationJob?.cancel()
        validationJob = viewModelScope.launch {
            if (!immediate) delay(350)
            val source = state.value.draft
            mutableState.update { it.copy(validating = true) }
            try {
                val validation = repository.validatePluginDraft(source)
                if (state.value.draft == source) {
                    mutableState.update {
                        it.copy(validating = false, validation = validation, draft = validation.draft)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (state.value.draft == source) {
                    mutableState.update {
                        it.copy(validating = false, error = error.message ?: "实时校验失败。")
                    }
                }
            }
        }
    }
}

internal fun defaultPrompt(template: PluginBuilderTemplate): String = when (template) {
    PluginBuilderTemplate.ChatAction -> "结合当前场景、人物关系和最近对话，生成一段自然、可直接发送的下一句。用户已有草稿：{{seed_text}}"
    PluginBuilderTemplate.GenerationEnhancer -> "保持人物性格、关系和世界设定一致；让每次回复自然推动当前场景，不替用户决定关键行动。"
    PluginBuilderTemplate.TemporaryNpc -> "根据当前场景生成一名有明确身份、动机和说话风格的临时 NPC，并让其自然加入对话。"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

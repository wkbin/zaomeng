package top.wkbin.zaomeng.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.client.platform.clientBase64Encode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.PluginDto
import top.wkbin.zaomeng.data.api.PluginPackageInspectionDto
import top.wkbin.zaomeng.data.api.PluginLogDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class PluginsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val plugins: List<PluginDto> = emptyList(),
    val busyPluginId: String = "",
    val packageBusy: Boolean = false,
    val packageInspection: PluginPackageInspectionDto? = null,
    val detailPlugin: PluginDto? = null,
    val detailLogs: List<PluginLogDto> = emptyList(),
    val detailLoading: Boolean = false,
    val configPlugin: PluginDto? = null,
    val configDraft: JsonObject = JsonObject(emptyMap()),
    val configSaving: Boolean = false,
    val message: String = "",
    val error: String = "",
)

class PluginsViewModel(
    private val repository: ZaomengRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PluginsUiState())
    val state: StateFlow<PluginsUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load(refresh: Boolean = false) {
        if (state.value.refreshing || state.value.busyPluginId.isNotBlank()) return
        viewModelScope.launch {
            mutableState.update { current ->
                current.copy(
                    loading = current.plugins.isEmpty(),
                    refreshing = refresh,
                    message = "",
                    error = "",
                )
            }
            try {
                val plugins = if (refresh) repository.refreshPlugins() else repository.listPlugins()
                mutableState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        plugins = plugins.sortedWith(compareByDescending<PluginDto> { plugin -> plugin.source == "official" }.thenBy { plugin -> plugin.name }),
                        message = if (refresh) "插件列表已刷新。" else "",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = error.message ?: "读取插件列表失败。",
                    )
                }
            }
        }
    }

    fun setEnabled(plugin: PluginDto, enabled: Boolean) {
        if (!plugin.executable) {
            mutableState.update {
                it.copy(error = plugin.capabilityNotice.ifBlank { "该插件当前不可执行。" }, message = "")
            }
            return
        }
        if (state.value.busyPluginId.isNotBlank() || plugin.enabled == enabled) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(busyPluginId = plugin.id, message = "", error = "")
            }
            try {
                val updated = if (enabled) {
                    repository.enablePlugin(plugin.id)
                } else {
                    repository.disablePlugin(plugin.id)
                }
                mutableState.update { current ->
                    current.copy(
                        busyPluginId = "",
                        plugins = current.plugins.map { item ->
                            if (item.id == updated.id) updated else item
                        },
                        message = if (enabled) "已启用「${plugin.name}」。" else "已停用「${plugin.name}」。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        busyPluginId = "",
                        error = error.message ?: "插件状态更新失败。",
                    )
                }
            }
        }
    }

    fun inspectPackage(filename: String, bytes: ByteArray) {
        if (state.value.packageBusy || state.value.busyPluginId.isNotBlank()) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(packageBusy = true, packageInspection = null, message = "", error = "")
            }
            try {
                val inspection = repository.inspectPluginPackage(
                    filename = filename,
                    contentBase64 = clientBase64Encode(bytes),
                )
                mutableState.update {
                    it.copy(packageBusy = false, packageInspection = inspection)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        packageBusy = false,
                        error = error.message ?: "检查插件包失败。",
                    )
                }
            }
        }
    }

    fun dismissPackageInspection() {
        if (!state.value.packageBusy) {
            mutableState.update { it.copy(packageInspection = null) }
        }
    }

    fun reportPackageReadError(error: Throwable) {
        mutableState.update {
            it.copy(error = error.message ?: "读取插件包失败。", message = "")
        }
    }

    fun installInspectedPackage() {
        val inspection = state.value.packageInspection ?: return
        if (state.value.packageBusy || inspection.operation == "blocked") return
        viewModelScope.launch {
            mutableState.update { it.copy(packageBusy = true, message = "", error = "") }
            try {
                val installed = repository.installPluginPackage(
                    token = inspection.token,
                    allowUpdate = inspection.operation == "update",
                )
                val plugins = repository.listPlugins()
                mutableState.update {
                    it.copy(
                        packageBusy = false,
                        packageInspection = null,
                        plugins = plugins.sortedPlugins(),
                        message = if (inspection.operation == "update") {
                            "已保存「${installed.name}」v${installed.version}；当前版本不会执行第三方代码。"
                        } else {
                            "已保存「${installed.name}」；当前版本不会执行第三方代码。"
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        packageBusy = false,
                        packageInspection = null,
                        error = error.message ?: "安装插件失败。",
                    )
                }
            }
        }
    }

    fun uninstall(plugin: PluginDto) {
        if (plugin.source != "third-party" || state.value.busyPluginId.isNotBlank()) return
        viewModelScope.launch {
            mutableState.update { it.copy(busyPluginId = plugin.id, message = "", error = "") }
            try {
                repository.uninstallPlugin(plugin.id)
                mutableState.update { current ->
                    current.copy(
                        busyPluginId = "",
                        plugins = current.plugins.filterNot { it.id == plugin.id },
                        message = "已卸载「${plugin.name}」。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        busyPluginId = "",
                        error = error.message ?: "卸载插件失败。",
                    )
                }
            }
        }
    }

    fun openDetails(plugin: PluginDto) {
        if (state.value.detailLoading) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    detailPlugin = plugin,
                    detailLogs = emptyList(),
                    detailLoading = true,
                    error = "",
                )
            }
            try {
                val logs = repository.listPluginLogs(plugin.id)
                mutableState.update { it.copy(detailLogs = logs, detailLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        detailLoading = false,
                        error = error.message ?: "读取插件日志失败。",
                    )
                }
            }
        }
    }

    fun closeDetails() {
        mutableState.update {
            it.copy(detailPlugin = null, detailLogs = emptyList(), detailLoading = false)
        }
    }

    fun openConfig(plugin: PluginDto) {
        mutableState.update {
            it.copy(configPlugin = plugin, configDraft = plugin.config, error = "")
        }
    }

    fun updateConfigValue(key: String, value: JsonElement) {
        mutableState.update {
            it.copy(configDraft = JsonObject(it.configDraft + (key to value)))
        }
    }

    fun closeConfig() {
        if (!state.value.configSaving) {
            mutableState.update {
                it.copy(configPlugin = null, configDraft = JsonObject(emptyMap()))
            }
        }
    }

    fun saveConfig() {
        val plugin = state.value.configPlugin ?: return
        if (state.value.configSaving) return
        viewModelScope.launch {
            mutableState.update { it.copy(configSaving = true, error = "", message = "") }
            try {
                val response = repository.updatePluginConfig(plugin.id, state.value.configDraft)
                mutableState.update { current ->
                    current.copy(
                        configPlugin = null,
                        configDraft = JsonObject(emptyMap()),
                        configSaving = false,
                        plugins = current.plugins.map { item ->
                            if (item.id == plugin.id) item.copy(config = response.config) else item
                        },
                        message = "已保存「${plugin.name}」的配置。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        configSaving = false,
                        error = error.message ?: "保存插件配置失败。",
                    )
                }
            }
        }
    }
}

private fun List<PluginDto>.sortedPlugins(): List<PluginDto> =
    sortedWith(
        compareByDescending<PluginDto> { it.source == "official" }
            .thenBy { it.name },
    )

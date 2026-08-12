package top.wkbin.zaomeng.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import okio.Sink
import top.wkbin.zaomeng.backend.BackendState
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.ModelCapabilityReportDto
import top.wkbin.zaomeng.data.api.ModelConnectionTestDto
import top.wkbin.zaomeng.data.api.ModelSettingsDto
import top.wkbin.zaomeng.data.api.PluginChatActionResponse
import top.wkbin.zaomeng.data.api.PluginConfigResponse
import top.wkbin.zaomeng.data.api.PluginDto
import top.wkbin.zaomeng.data.api.PluginLogDto
import top.wkbin.zaomeng.data.api.PluginPackageInspectionDto
import top.wkbin.zaomeng.data.api.PluginTemporaryNpcGeneratorResponse
import top.wkbin.zaomeng.data.api.SaveModelSettingsRequest
import top.wkbin.zaomeng.data.api.TestModelSettingsRequest
import top.wkbin.zaomeng.data.api.UninstallPluginResponse
import top.wkbin.zaomeng.data.preferences.AppPreferences

interface AppRuntimeRepository {
    val backendState: StateFlow<BackendState>
    val preferences: Flow<AppPreferences>

    fun startBackend()
    fun retryBackend()
}

interface ModelSettingsRepository {
    suspend fun getModelSettings(): ModelSettingsDto
    suspend fun saveModelSettings(request: SaveModelSettingsRequest): ModelSettingsDto
    suspend fun testModelSettings(request: TestModelSettingsRequest): ModelConnectionTestDto
    suspend fun detectModelCapabilities(request: TestModelSettingsRequest): ModelCapabilityReportDto
    suspend fun activateModelProfile(profileId: String): ModelSettingsDto
    suspend fun deleteModelProfile(profileId: String): ModelSettingsDto
}

interface DiagnosticsRepository {
    suspend fun exportDiagnostics(destination: Sink): Long
}

interface PluginRepository {
    suspend fun listPlugins(): List<PluginDto>
    suspend fun refreshPlugins(): List<PluginDto>
    suspend fun inspectPluginPackage(filename: String, contentBase64: String): PluginPackageInspectionDto
    suspend fun installPluginPackage(token: String, allowUpdate: Boolean): PluginDto
    suspend fun enablePlugin(pluginId: String): PluginDto
    suspend fun disablePlugin(pluginId: String): PluginDto
    suspend fun uninstallPlugin(pluginId: String): UninstallPluginResponse
    suspend fun listPluginLogs(pluginId: String): List<PluginLogDto>
    suspend fun updatePluginConfig(pluginId: String, config: JsonObject): PluginConfigResponse

    suspend fun invokePluginChatAction(
        runId: String,
        sessionId: String,
        pluginId: String,
        actionId: String,
        seedText: String = "",
        direction: String = "",
    ): PluginChatActionResponse

    suspend fun invokePluginTemporaryNpcGenerator(
        runId: String,
        sessionId: String,
        pluginId: String,
        generatorId: String,
        direction: String = "",
    ): PluginTemporaryNpcGeneratorResponse

    suspend fun setGenerationEnhancerState(
        runId: String,
        sessionId: String,
        pluginId: String,
        enhancerId: String,
        enabled: Boolean,
    ): DialogueSessionDto
}

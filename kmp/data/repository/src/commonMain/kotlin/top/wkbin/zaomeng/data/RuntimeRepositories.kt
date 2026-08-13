package top.wkbin.zaomeng.data

import io.ktor.client.call.body
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import okio.Sink
import okio.buffer
import okio.use
import top.wkbin.zaomeng.backend.BackendController
import top.wkbin.zaomeng.backend.BackendState
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.InspectPluginPackageRequest
import top.wkbin.zaomeng.data.api.InstallPluginPackageRequest
import top.wkbin.zaomeng.data.api.KtorDiagnosticsClient
import top.wkbin.zaomeng.data.api.KtorModelSettingsClient
import top.wkbin.zaomeng.data.api.KtorPluginClient
import top.wkbin.zaomeng.data.api.ModelCapabilityReportDto
import top.wkbin.zaomeng.data.api.ModelConnectionTestDto
import top.wkbin.zaomeng.data.api.ModelSettingsDto
import top.wkbin.zaomeng.data.api.PluginChatActionRequest
import top.wkbin.zaomeng.data.api.PluginChatActionResponse
import top.wkbin.zaomeng.data.api.PluginConfigResponse
import top.wkbin.zaomeng.data.api.PluginDto
import top.wkbin.zaomeng.data.api.PluginLogDto
import top.wkbin.zaomeng.data.api.PluginPackageInspectionDto
import top.wkbin.zaomeng.data.api.PluginBuilderValidationDto
import top.wkbin.zaomeng.data.api.PluginDraft
import top.wkbin.zaomeng.data.api.PackagePluginDraftRequest
import top.wkbin.zaomeng.data.api.ValidatePluginDraftRequest
import top.wkbin.zaomeng.data.api.PluginTemporaryNpcGeneratorRequest
import top.wkbin.zaomeng.data.api.PluginTemporaryNpcGeneratorResponse
import top.wkbin.zaomeng.data.api.SaveModelSettingsRequest
import top.wkbin.zaomeng.data.api.TestModelSettingsRequest
import top.wkbin.zaomeng.data.api.UninstallPluginResponse
import top.wkbin.zaomeng.data.preferences.AppPreferences
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.platform.SecureKeyValueStore
import top.wkbin.zaomeng.platform.SecureStoreNames
import top.wkbin.zaomeng.client.platform.clientBase64Encode

class AppRuntimeRepositoryImpl(
    private val backend: BackendController,
    private val appPreferences: AppPreferencesRepository,
) : AppRuntimeRepository {
    override val backendState: StateFlow<BackendState> = backend.state
    override val preferences: Flow<AppPreferences> = appPreferences.preferences

    override fun startBackend() = backend.start()
    override fun retryBackend() = backend.retry()
}

class ModelSettingsRepositoryImpl(
    private val ktorModelSettings: KtorModelSettingsClient,
    private val secureStore: SecureKeyValueStore,
) : ModelSettingsRepository {
    override suspend fun getModelSettings(): ModelSettingsDto = repositoryRequest {
        ktorModelSettings.get()
    }

    override suspend fun saveModelSettings(request: SaveModelSettingsRequest): ModelSettingsDto = repositoryRequest {
        ktorModelSettings.save(request).also { saved ->
            secureStore.put(
                SecureStoreNames.secretName(request.profileId.ifBlank { saved.activeProfileId }),
                request.apiKey,
            )
        }
    }

    override suspend fun testModelSettings(request: TestModelSettingsRequest): ModelConnectionTestDto = repositoryRequest {
        ktorModelSettings.test(request)
    }

    override suspend fun detectModelCapabilities(request: TestModelSettingsRequest): ModelCapabilityReportDto = repositoryRequest {
        ktorModelSettings.detectCapabilities(request)
    }

    override suspend fun activateModelProfile(profileId: String): ModelSettingsDto = repositoryRequest {
        ktorModelSettings.activate(profileId)
    }

    override suspend fun deleteModelProfile(profileId: String): ModelSettingsDto = repositoryRequest {
        ktorModelSettings.delete(profileId).also {
            secureStore.remove(SecureStoreNames.secretName(profileId))
        }
    }
}

class DiagnosticsRepositoryImpl(
    private val ktorDiagnostics: KtorDiagnosticsClient,
) : DiagnosticsRepository {
    override suspend fun exportDiagnostics(destination: Sink): Long = repositoryRequest {
        ktorDiagnostics.export(destination)
    }
}

class PluginRepositoryImpl(
    private val ktorPlugins: KtorPluginClient,
) : PluginRepository {
    override suspend fun listPlugins(): List<PluginDto> = repositoryRequest {
        ktorPlugins.list().items
    }

    override suspend fun refreshPlugins(): List<PluginDto> = repositoryRequest {
        ktorPlugins.refresh().items
    }

    override suspend fun inspectPluginPackage(
        filename: String,
        contentBase64: String,
    ): PluginPackageInspectionDto = repositoryRequest {
        ktorPlugins.inspect(InspectPluginPackageRequest(filename, contentBase64))
    }

    override suspend fun installPluginPackage(
        token: String,
        allowUpdate: Boolean,
    ): PluginDto = repositoryRequest {
        ktorPlugins.install(
            token,
            InstallPluginPackageRequest(
                confirmPermissions = true,
                allowUpdate = allowUpdate,
            ),
        )
    }

    override suspend fun enablePlugin(pluginId: String): PluginDto = repositoryRequest {
        ktorPlugins.enable(pluginId)
    }

    override suspend fun disablePlugin(pluginId: String): PluginDto = repositoryRequest {
        ktorPlugins.disable(pluginId)
    }

    override suspend fun uninstallPlugin(pluginId: String): UninstallPluginResponse = repositoryRequest {
        ktorPlugins.uninstall(pluginId)
    }

    override suspend fun listPluginLogs(pluginId: String): List<PluginLogDto> = repositoryRequest {
        ktorPlugins.logs(pluginId).items
    }

    override suspend fun updatePluginConfig(
        pluginId: String,
        config: JsonObject,
    ): PluginConfigResponse = repositoryRequest {
        ktorPlugins.updateConfig(pluginId, config)
    }

    override suspend fun validatePluginDraft(draft: PluginDraft): PluginBuilderValidationDto = repositoryRequest {
        ktorPlugins.validateBuilder(ValidatePluginDraftRequest(draft))
    }

    override suspend fun installPluginDraft(draft: PluginDraft): PluginDto = repositoryRequest {
        val validation = ktorPlugins.validateBuilder(ValidatePluginDraftRequest(draft))
        if (!validation.valid) {
            throw ApiRequestException(validation.issues.joinToString("；") { it.message }.ifBlank { "插件草稿未通过校验。" })
        }
        val response = ktorPlugins.packageBuilder(PackagePluginDraftRequest(validation.draft))
        val bytes: ByteArray = response.body()
        val inspection = ktorPlugins.inspect(
            InspectPluginPackageRequest(validation.filename, clientBase64Encode(bytes)),
        )
        ktorPlugins.install(
            inspection.token,
            InstallPluginPackageRequest(
                confirmPermissions = true,
                allowUpdate = inspection.operation == "update",
            ),
        )
    }

    override suspend fun exportPluginDraft(draft: PluginDraft, destination: Sink): Long = repositoryRequest {
        val response = ktorPlugins.packageBuilder(PackagePluginDraftRequest(draft))
        val bytes: ByteArray = response.body()
        destination.buffer().use { it.write(bytes) }
        bytes.size.toLong()
    }

    override suspend fun invokePluginChatAction(
        runId: String,
        sessionId: String,
        pluginId: String,
        actionId: String,
        seedText: String,
        direction: String,
    ): PluginChatActionResponse = repositoryRequest {
        val result = ktorPlugins.chatAction(
            runId,
            sessionId,
            pluginId,
            actionId,
            PluginChatActionRequest(seedText = seedText, direction = direction),
        )
        if (result.suggestion.isBlank() && result.suggestions.none { it.suggestion.isNotBlank() }) {
            throw ApiRequestException("插件没有返回可写入输入框的内容。")
        }
        result
    }

    override suspend fun invokePluginTemporaryNpcGenerator(
        runId: String,
        sessionId: String,
        pluginId: String,
        generatorId: String,
        direction: String,
    ): PluginTemporaryNpcGeneratorResponse = repositoryRequest {
        ktorPlugins.temporaryNpcGenerator(
            runId,
            sessionId,
            pluginId,
            generatorId,
            PluginTemporaryNpcGeneratorRequest(direction = direction),
        )
    }

    override suspend fun setGenerationEnhancerState(
        runId: String,
        sessionId: String,
        pluginId: String,
        enhancerId: String,
        enabled: Boolean,
    ): DialogueSessionDto = repositoryRequest {
        ktorPlugins.setEnhancerState(
            runId,
            sessionId,
            pluginId,
            enhancerId,
            enabled,
        )
    }
}

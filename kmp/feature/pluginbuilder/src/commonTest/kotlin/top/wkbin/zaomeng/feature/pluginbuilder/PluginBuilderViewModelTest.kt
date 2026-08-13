package top.wkbin.zaomeng.feature.pluginbuilder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import okio.Sink
import top.wkbin.zaomeng.data.PluginRepository
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.PluginBuilderValidationDto
import top.wkbin.zaomeng.data.api.PluginChatActionResponse
import top.wkbin.zaomeng.data.api.PluginConfigResponse
import top.wkbin.zaomeng.data.api.PluginDto
import top.wkbin.zaomeng.data.api.PluginDraft
import top.wkbin.zaomeng.data.api.PluginLogDto
import top.wkbin.zaomeng.data.api.PluginPackageInspectionDto
import top.wkbin.zaomeng.data.api.PluginTemporaryNpcGeneratorResponse
import top.wkbin.zaomeng.data.api.UninstallPluginResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PluginBuilderViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `name generates id and variable chip updates natural language prompt`() = runTest(dispatcher) {
        val repository = FakePluginRepository()
        val viewModel = PluginBuilderViewModel(repository)
        advanceUntilIdle()

        viewModel.updateName("温柔接话")
        viewModel.insertVariable("{{config.tone}}")
        advanceTimeBy(351)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.draft.id.startsWith("plugin-"))
        assertEquals("温柔接话", viewModel.state.value.draft.title)
        assertTrue(viewModel.state.value.draft.prompt.endsWith("{{config.tone}}"))
        assertTrue(repository.validations >= 2)
    }

    @Test
    fun `valid draft prepares requested filename and installs through repository`() = runTest(dispatcher) {
        val repository = FakePluginRepository()
        val viewModel = PluginBuilderViewModel(repository)
        viewModel.updateName("快捷回复")
        advanceTimeBy(351)
        advanceUntilIdle()

        viewModel.prepareExport()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.exportDestinationPending)
        assertEquals("快捷回复-0.1.0.zaomeng-plugin.zip", viewModel.state.value.pendingExportFilename)

        viewModel.installForTesting()
        advanceUntilIdle()
        assertEquals(1, repository.installations)
        assertTrue(viewModel.state.value.message.contains("已安装"))
    }
}

private class FakePluginRepository : PluginRepository {
    var validations = 0
    var installations = 0

    override suspend fun validatePluginDraft(draft: PluginDraft): PluginBuilderValidationDto {
        validations++
        return PluginBuilderValidationDto(
            valid = draft.name.isNotBlank() && draft.prompt.isNotBlank(),
            draft = draft,
            manifestJson = "{}",
            filename = "${draft.name}-${draft.version}.zaomeng-plugin.zip",
        )
    }

    override suspend fun installPluginDraft(draft: PluginDraft): PluginDto {
        installations++
        return PluginDto(id = draft.id, name = draft.name, version = draft.version, executable = true)
    }

    override suspend fun exportPluginDraft(draft: PluginDraft, destination: Sink): Long = 2
    override suspend fun listPlugins(): List<PluginDto> = emptyList()
    override suspend fun refreshPlugins(): List<PluginDto> = emptyList()
    override suspend fun inspectPluginPackage(filename: String, contentBase64: String): PluginPackageInspectionDto = PluginPackageInspectionDto()
    override suspend fun installPluginPackage(token: String, allowUpdate: Boolean): PluginDto = PluginDto()
    override suspend fun enablePlugin(pluginId: String): PluginDto = PluginDto()
    override suspend fun disablePlugin(pluginId: String): PluginDto = PluginDto()
    override suspend fun uninstallPlugin(pluginId: String): UninstallPluginResponse = UninstallPluginResponse()
    override suspend fun listPluginLogs(pluginId: String): List<PluginLogDto> = emptyList()
    override suspend fun updatePluginConfig(pluginId: String, config: JsonObject): PluginConfigResponse = PluginConfigResponse()
    override suspend fun invokePluginChatAction(
        runId: String,
        sessionId: String,
        pluginId: String,
        actionId: String,
        seedText: String,
        direction: String,
    ): PluginChatActionResponse = PluginChatActionResponse()

    override suspend fun invokePluginTemporaryNpcGenerator(
        runId: String,
        sessionId: String,
        pluginId: String,
        generatorId: String,
        direction: String,
    ): PluginTemporaryNpcGeneratorResponse = PluginTemporaryNpcGeneratorResponse()

    override suspend fun setGenerationEnhancerState(
        runId: String,
        sessionId: String,
        pluginId: String,
        enhancerId: String,
        enabled: Boolean,
    ): DialogueSessionDto = DialogueSessionDto()
}

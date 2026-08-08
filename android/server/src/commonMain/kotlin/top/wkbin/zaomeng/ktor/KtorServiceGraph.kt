package top.wkbin.zaomeng.ktor

import top.wkbin.zaomeng.platform.ServerPlatform
import top.wkbin.zaomeng.ktor.services.*

/** Application-scoped Ktor services. Routes receive dependencies from this graph. */
class KtorServiceGraph(platform: ServerPlatform) {
    val storage = StorageService(platform.dataRoot)
    val diagnostics = DiagnosticsService(storage.getStorageRoot(), storage)
    val modelApiKeys = ModelApiKeyService(platform.secureStore())
    val promptLoader = PromptLoader(platform.promptSource)
    val llm = LlmClient(modelApiKeys, storage)
    val worldMemory = WorldMemoryService(storage)
    val dialogue = DialogueService(storage, llm, promptLoader, worldMemory)
    val dialogueStream = DialogueStreamService(storage, llm, promptLoader, dialogue)
    val suggestions = SuggestionsService(storage, llm, promptLoader)
    val dialogueAdvanced = DialogueAdvancedService(storage, llm, promptLoader)
    val sessionManagement = SessionManagementService(storage, dialogue, worldMemory)
    val distillExecutor = DistillExecutor(storage, llm, promptLoader)
    val runManagement = RunManagementService(storage, distillExecutor)
    val runPackages = RunPackageService(storage)
    val settingsManagement = SettingsManagementService(storage, modelApiKeys)
    val chapter = ChapterService(storage, llm, promptLoader)
    val chapterManagement = ChapterManagementService(storage, sessionManagement, llm, promptLoader)
    val cards = CardsService(storage, llm, promptLoader)
    val cardsManagement = CardsManagementService(storage)
    val persona = PersonaService(storage, llm, promptLoader)
    val relations = RelationsService(storage)
    val runOperations = RunOperationsService(
        storage,
        runManagement,
        runPackages,
        distillExecutor,
    )
    val plugins = PluginService(storage, top.wkbin.zaomeng.plugins.builtin.BuiltinPlugins.all)
    val pluginHost = PluginHostImpl(storage, llm, dialogueAdvanced, suggestions, plugins)
    val pluginOperations = PluginOperationsService(storage, plugins, pluginHost)
}

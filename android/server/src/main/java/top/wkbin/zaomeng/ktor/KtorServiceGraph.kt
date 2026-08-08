package top.wkbin.zaomeng.ktor

import android.content.Context
import top.wkbin.zaomeng.ktor.services.*

/** Application-scoped Ktor services. Routes receive dependencies from this graph. */
class KtorServiceGraph(context: Context) {
    private val applicationContext = context.applicationContext
    val storage = StorageService(applicationContext)
    val diagnostics = DiagnosticsService(storage.getStorageRoot(), storage)
    val modelApiKeys = ModelApiKeyService(applicationContext)
    val promptLoader = PromptLoader(applicationContext)
    val llm = LlmClient(applicationContext, modelApiKeys, storage)
    val dialogue = DialogueService(storage, llm, promptLoader)
    val dialogueStream = DialogueStreamService(applicationContext, storage, llm, promptLoader, dialogue)
    val suggestions = SuggestionsService(applicationContext, storage, llm, promptLoader)
    val dialogueAdvanced = DialogueAdvancedService(storage, llm, promptLoader)
    val sessionManagement = SessionManagementService(storage, dialogue)
    val runManagement = RunManagementService(storage)
    val runPackages = RunPackageService(storage)
    val settingsManagement = SettingsManagementService(applicationContext, storage, modelApiKeys)
    val chapter = ChapterService(storage, applicationContext)
    val chapterManagement = ChapterManagementService(storage, sessionManagement, llm, promptLoader)
    val cards = CardsService(applicationContext)
    val cardsManagement = CardsManagementService(storage)
    val persona = PersonaService(storage, llm, promptLoader)
    val worldMemory = WorldMemoryService(storage)
    val relations = RelationsService(storage)
    val runOperations = RunOperationsService(storage, runManagement, runPackages, DistillExecutor(storage, llm))
    val plugins = PluginService(storage, top.wkbin.zaomeng.plugins.builtin.BuiltinPlugins.all)
    val pluginHost = PluginHostImpl(storage, llm, dialogueAdvanced, suggestions, plugins)
    val pluginOperations = PluginOperationsService(storage, plugins, pluginHost)
}

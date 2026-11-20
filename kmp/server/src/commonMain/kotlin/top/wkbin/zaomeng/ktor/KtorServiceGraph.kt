package top.wkbin.zaomeng.ktor

import top.wkbin.zaomeng.platform.ServerPlatform
import top.wkbin.zaomeng.db.DomainStore
import top.wkbin.zaomeng.db.RoomDocumentStore
import top.wkbin.zaomeng.db.buildZaomengDatabase
import top.wkbin.zaomeng.ktor.services.*

/** Application-scoped Ktor services. Routes receive dependencies from this graph. */
class KtorServiceGraph(platform: ServerPlatform) {
    private val database = buildZaomengDatabase(platform.databaseBuilder())
    private val documentStore = RoomDocumentStore(database)
    val storage = StorageService(
        platform.dataRoot,
        documentStore,
        DomainStore(platform.dataRoot, database.domainDao(), documentStore),
    )
    val modelRequests = ModelRequestDiagnostics()
    val diagnostics = DiagnosticsService(storage.getStorageRoot(), storage, modelRequests)
    val modelApiKeys = ModelApiKeyService(platform.secureStore())
    val promptLoader = PromptLoader(platform.promptSource)
    val llm = LlmClient(modelApiKeys, storage, requestDiagnostics = modelRequests)
    val worldMemory = WorldMemoryService(storage)
    val originalKnowledge = OriginalKnowledgeService(storage)
    val plugins = PluginService(storage, top.wkbin.zaomeng.plugins.builtin.BuiltinPlugins.all)
    val pluginRules = PluginRuleEngine(storage, plugins)
    val replyOperations = DialogueReplyOperationCoordinator(storage)
    val projections = SessionProjectionService(storage, llm, promptLoader)
    val historyRetrieval = DialogueHistoryRetrievalService(storage, llm, promptLoader)
    val dialogue = DialogueService(storage, llm, promptLoader, worldMemory, pluginRules, replyOperations, projections, historyRetrieval)
    val dialogueStream = DialogueStreamService(storage, llm, promptLoader, dialogue, replyOperations, projections)
    val suggestions = SuggestionsService(storage, llm, promptLoader)
    val dialogueAdvanced = DialogueAdvancedService(storage, llm, promptLoader, dialogue, projections)
    val sessionManagement = SessionManagementService(storage, dialogue, worldMemory, projections)
    val distillExecutor = DistillExecutor(storage, llm, promptLoader, originalKnowledge)
    val runManagement = RunManagementService(storage, distillExecutor, dialogue)
    val runPackages = RunPackageService(storage)
    val settingsManagement = SettingsManagementService(storage, modelApiKeys)
    val chapter = ChapterService(storage, llm, promptLoader)
    val chapterManagement = ChapterManagementService(storage, sessionManagement, llm, promptLoader, projections)
    val cards = CardsService(storage, llm, promptLoader)
    val cardsManagement = CardsManagementService(storage)
    val relations = RelationsService(storage)
    val persona = PersonaService(storage, llm, promptLoader, relations)
    val runOperations = RunOperationsService(
        storage,
        runManagement,
        runPackages,
        distillExecutor,
    )
    val pluginHost = PluginHostImpl(storage, llm, dialogueAdvanced, suggestions, plugins, projections)
    val pluginOperations = PluginOperationsService(storage, plugins, pluginHost)
    val pluginBuilder = PluginBuilderService { messages ->
        llm.chatCompletion(
            messages = messages,
            temperature = 0.2,
            maxTokens = 1_600,
            requireJsonObject = true,
        ).choices.firstOrNull()?.message?.content.orEmpty()
    }

    init {
        // Distillation jobs are process-local coroutines. If the backend was restarted,
        // a persisted "running" manifest no longer has a worker behind it; expose it as
        // an interruptible run so the existing resume flow can recover it.
        distillExecutor.markPersistedRunsInterrupted()
        dialogue.recoverDerivedOperations()
    }
}

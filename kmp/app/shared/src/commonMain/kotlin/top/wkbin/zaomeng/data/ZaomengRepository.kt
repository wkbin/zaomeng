package top.wkbin.zaomeng.data

import okio.Path.Companion.toPath
import okio.Path
import top.wkbin.zaomeng.backend.BackendState
import top.wkbin.zaomeng.backend.BackendController
import top.wkbin.zaomeng.backend.SecureStoreNames
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.SecureKeyValueStore
import top.wkbin.zaomeng.platform.base64Encode
import top.wkbin.zaomeng.platform.platformIoDispatcher
import top.wkbin.zaomeng.data.api.CreateDialogueSessionRequest
import top.wkbin.zaomeng.data.api.CreateRunRequest
import top.wkbin.zaomeng.data.api.CreateCrossoverSpaceRequest
import top.wkbin.zaomeng.data.api.CrossoverParticipantRequest
import top.wkbin.zaomeng.data.api.BranchDialogueTurnRequest
import top.wkbin.zaomeng.data.api.BranchDialogueSceneRequest
import top.wkbin.zaomeng.data.api.ArchiveDialogueChapterRequest
import top.wkbin.zaomeng.data.api.AskBookQuestionRequest
import top.wkbin.zaomeng.data.api.AskBookResponseDto
import top.wkbin.zaomeng.data.api.ChapterDto
import top.wkbin.zaomeng.data.api.DeleteRunResponse
import top.wkbin.zaomeng.data.api.DeleteStatusDto
import top.wkbin.zaomeng.data.api.DeleteSessionsRequest
import top.wkbin.zaomeng.data.api.DeleteSessionsResponse
import top.wkbin.zaomeng.data.api.DialogueDirectorRequest
import top.wkbin.zaomeng.data.api.DialogueMemoryDto
import top.wkbin.zaomeng.data.api.MemoryQualityReportDto
import top.wkbin.zaomeng.data.api.DialogueReplyRequest
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.DialogueStreamEvent
import top.wkbin.zaomeng.data.api.DialogueSuggestionRequest
import top.wkbin.zaomeng.data.api.ChatSearchResultDto
import top.wkbin.zaomeng.data.api.MessagesResponse
import top.wkbin.zaomeng.data.api.ExportedRunPackage
import top.wkbin.zaomeng.data.api.ExportedChapterManuscript
import top.wkbin.zaomeng.data.api.ImportRunPackageRequest
import top.wkbin.zaomeng.data.api.LibraryPackageImportDto
import top.wkbin.zaomeng.data.api.EstimateSamplingRequest
import top.wkbin.zaomeng.data.api.ModelSettingsDto
import top.wkbin.zaomeng.data.api.SamplingPlanDto
import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
import top.wkbin.zaomeng.data.api.PersonaRepairProposalDto
import top.wkbin.zaomeng.data.api.PersonaAvatarDto
import top.wkbin.zaomeng.data.api.PersonaReviewDto
import top.wkbin.zaomeng.data.api.PluginDto
import top.wkbin.zaomeng.data.api.PluginChatActionRequest
import top.wkbin.zaomeng.data.api.PluginChatActionResponse
import top.wkbin.zaomeng.data.api.PluginTemporaryNpcGeneratorRequest
import top.wkbin.zaomeng.data.api.PluginTemporaryNpcGeneratorResponse
import top.wkbin.zaomeng.data.api.InspectPluginPackageRequest
import top.wkbin.zaomeng.data.api.InstallPluginPackageRequest
import top.wkbin.zaomeng.data.api.PluginPackageInspectionDto
import top.wkbin.zaomeng.data.api.PluginLogDto
import top.wkbin.zaomeng.data.api.PluginConfigResponse
import top.wkbin.zaomeng.data.api.UpdatePluginConfigRequest
import top.wkbin.zaomeng.data.api.UninstallPluginResponse
import top.wkbin.zaomeng.data.api.RelationDetailsDto
import top.wkbin.zaomeng.data.api.RelationItemDto
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.data.api.RecommendSceneCardsRequest
import top.wkbin.zaomeng.data.api.RestartRunRequest
import top.wkbin.zaomeng.data.api.RedistillSuggestionsDto
import top.wkbin.zaomeng.data.api.ReorderChapterRequest
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.SaveModelSettingsRequest
import top.wkbin.zaomeng.data.api.SetGenerationEnhancerStateRequest
import top.wkbin.zaomeng.data.api.TestModelSettingsRequest
import top.wkbin.zaomeng.data.api.KtorModelSettingsClient
import top.wkbin.zaomeng.data.api.KtorPluginClient
import top.wkbin.zaomeng.data.api.KtorRunsClient
import top.wkbin.zaomeng.data.api.KtorRunManagementClient
import top.wkbin.zaomeng.data.api.KtorSessionClient
import top.wkbin.zaomeng.data.api.KtorChapterClient
import top.wkbin.zaomeng.data.api.KtorDiagnosticsClient
import top.wkbin.zaomeng.data.api.KtorCardsClient
import top.wkbin.zaomeng.data.api.KtorPersonaClient
import top.wkbin.zaomeng.data.api.KtorDialogueClient
import top.wkbin.zaomeng.data.api.KtorWorldMemoryClient
import top.wkbin.zaomeng.data.api.KtorRelationsClient
import top.wkbin.zaomeng.data.api.KtorRunOpsClient
import top.wkbin.zaomeng.data.api.KtorOriginalKnowledgeClient
import top.wkbin.zaomeng.data.api.OriginalKnowledgeEntryDto
import top.wkbin.zaomeng.data.api.SaveChapterRequest
import top.wkbin.zaomeng.data.api.SearchResultDto
import top.wkbin.zaomeng.data.api.SessionRefDto
import top.wkbin.zaomeng.data.api.SessionsResponse
import top.wkbin.zaomeng.data.api.TranscriptItemDto
import top.wkbin.zaomeng.data.api.toDialogueSessionDto
import top.wkbin.zaomeng.data.api.SuggestPersonaFieldRequest
import top.wkbin.zaomeng.data.api.SuggestPersonaFieldResponse
import top.wkbin.zaomeng.data.api.SuggestRedistillSegmentsRequest
import top.wkbin.zaomeng.data.api.SwitchDialogueSceneRequest
import top.wkbin.zaomeng.data.api.UpdateRelationDetailRequest
import top.wkbin.zaomeng.data.api.UpdateDialogueBranchMetaRequest
import top.wkbin.zaomeng.data.api.UpdateDialogueRelationLockRequest
import top.wkbin.zaomeng.data.api.UpdateDialogueSessionTitleRequest
import top.wkbin.zaomeng.data.api.SaveWorldFactRequest
import top.wkbin.zaomeng.data.api.WorldMemoryDto
import top.wkbin.zaomeng.data.api.WorldFactDto
import top.wkbin.zaomeng.data.api.UpsertDialogueMemoryRequest
import top.wkbin.zaomeng.data.preferences.AppPreferences
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.domain.chat.ChatSessionGateway
import top.wkbin.zaomeng.domain.distill.DistillPlanningGateway
import top.wkbin.zaomeng.domain.run.RunReviewGateway

class ZaomengRepository(
    private val backend: BackendController,
    private val appPreferences: AppPreferencesRepository,
    private val secureStore: SecureKeyValueStore,
    private val ktorModelSettings: KtorModelSettingsClient,
    private val ktorPlugins: KtorPluginClient,
    private val ktorRuns: KtorRunsClient,
    private val ktorRunManagement: KtorRunManagementClient,
    private val ktorSessions: KtorSessionClient,
    private val ktorChapters: KtorChapterClient,
    private val ktorDiagnostics: KtorDiagnosticsClient,
    private val ktorCards: KtorCardsClient,
    private val ktorPersona: KtorPersonaClient,
    private val ktorDialogue: KtorDialogueClient,
    private val ktorWorldMemory: KtorWorldMemoryClient,
    private val ktorRelations: KtorRelationsClient,
    private val ktorRunOps: KtorRunOpsClient,
    private val ktorOriginalKnowledge: KtorOriginalKnowledgeClient,
) : ChatSessionGateway, DistillPlanningGateway, RunReviewGateway {
    private val avatarCache = mutableMapOf<String, ByteArray>()
    val backendState: StateFlow<BackendState> = backend.state
    val preferences: Flow<AppPreferences> = appPreferences.preferences

    fun startBackend() = backend.start()
    fun retryBackend() = backend.retry()

    suspend fun getModelSettings(): ModelSettingsDto = request {
        ktorModelSettings.get()
    }

    suspend fun saveModelSettings(request: SaveModelSettingsRequest): ModelSettingsDto = request {
        ktorModelSettings.save(request).also { saved ->
            secureStore.put(
                SecureStoreNames.secretName(request.profileId.ifBlank { saved.activeProfileId }),
                request.apiKey,
            )
        }
    }

    suspend fun testModelSettings(request: TestModelSettingsRequest) = request {
        ktorModelSettings.test(request)
    }

    suspend fun detectModelCapabilities(request: TestModelSettingsRequest) = request {
        ktorModelSettings.detectCapabilities(request)
    }

    suspend fun activateModelProfile(profileId: String): ModelSettingsDto = request {
        ktorModelSettings.activate(profileId)
    }

    suspend fun deleteModelProfile(profileId: String): ModelSettingsDto = request {
        ktorModelSettings.delete(profileId).also {
            secureStore.remove(SecureStoreNames.secretName(profileId))
        }
    }

    suspend fun listPlugins(): List<PluginDto> = request {
        ktorPlugins.list().items
    }

    suspend fun refreshPlugins(): List<PluginDto> = request {
        ktorPlugins.refresh().items
    }

    suspend fun inspectPluginPackage(
        filename: String,
        contentBase64: String,
    ): PluginPackageInspectionDto = request {
        ktorPlugins.inspect(InspectPluginPackageRequest(filename, contentBase64))
    }

    suspend fun installPluginPackage(
        token: String,
        allowUpdate: Boolean,
    ): PluginDto = request {
        ktorPlugins.install(
            token,
            InstallPluginPackageRequest(
                confirmPermissions = true,
                allowUpdate = allowUpdate,
            ),
        )
    }

    suspend fun enablePlugin(pluginId: String): PluginDto = request {
        ktorPlugins.enable(pluginId)
    }

    suspend fun disablePlugin(pluginId: String): PluginDto = request {
        ktorPlugins.disable(pluginId)
    }

    suspend fun uninstallPlugin(pluginId: String): UninstallPluginResponse = request {
        ktorPlugins.uninstall(pluginId)
    }

    suspend fun listPluginLogs(pluginId: String): List<PluginLogDto> = request {
        ktorPlugins.logs(pluginId).items
    }

    suspend fun updatePluginConfig(
        pluginId: String,
        config: kotlinx.serialization.json.JsonObject,
    ): PluginConfigResponse = request {
        ktorPlugins.updateConfig(pluginId, config)
    }

    suspend fun invokePluginChatAction(
        runId: String,
        sessionId: String,
        pluginId: String,
        actionId: String,
        seedText: String = "",
        direction: String = "",
    ): PluginChatActionResponse = request {
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

    suspend fun invokePluginTemporaryNpcGenerator(
        runId: String,
        sessionId: String,
        pluginId: String,
        generatorId: String,
        direction: String = "",
    ): PluginTemporaryNpcGeneratorResponse = request {
        ktorPlugins.temporaryNpcGenerator(
            runId,
            sessionId,
            pluginId,
            generatorId,
            PluginTemporaryNpcGeneratorRequest(direction = direction),
        )
    }

    suspend fun setGenerationEnhancerState(
        runId: String,
        sessionId: String,
        pluginId: String,
        enhancerId: String,
        enabled: Boolean,
    ): DialogueSessionDto = request {
        ktorPlugins.setEnhancerState(
            runId,
            sessionId,
            pluginId,
            enhancerId,
            enabled,
        )
    }

    suspend fun exportDiagnostics(destination: okio.Sink): Long = request {
        ktorDiagnostics.export(destination)
    }

    suspend fun listRuns(): List<RunManifestDto> = request {
        ktorRuns.list().items
    }

    suspend fun createNovel(
        filename: String,
        bytes: ByteArray,
        characters: List<String>,
        maxSentences: Int,
        maxChars: Int,
        autoRun: Boolean = true,
    ): RunManifestDto {
        val payload = withContext(Dispatchers.Default) {
            CreateRunRequest(
                novelName = filename,
                novelContentBase64 = base64Encode(bytes),
                characters = characters,
                maxSentences = maxSentences,
                maxChars = maxChars,
                autoRun = autoRun,
                deferRun = !autoRun,
            )
        }
        return request {
            val run = ktorRunManagement.create(payload)
            appPreferences.rememberRun(run.runId)
            run
        }
    }

    override suspend fun estimateSampling(
        charCount: Int,
        sentenceCount: Int,
        characterCount: Int,
        maxSentences: Int,
        maxChars: Int,
    ): SamplingPlanDto = request {
        ktorRunOps.estimateSampling(
            EstimateSamplingRequest(
                charCount = charCount,
                sentenceCount = sentenceCount,
                characterCount = characterCount,
                maxSentences = maxSentences,
                maxChars = maxChars,
            ),
        )
    }

    suspend fun importPackage(
        filename: String,
        bytes: ByteArray,
        libraryPackage: LibraryPackageImportDto? = null,
    ): RunManifestDto {
        val payload = withContext(Dispatchers.Default) {
            ImportRunPackageRequest(
                filename = filename,
                contentBase64 = base64Encode(bytes),
                libraryPackage = libraryPackage,
            )
        }
        return request {
            val run = ktorRunManagement.import(payload)
            appPreferences.rememberRun(run.runId)
            run
        }
    }

    suspend fun saveImportDefaults(characters: String, autoDistill: Boolean = true) {
        appPreferences.saveImportDefaults(characters, autoDistill)
    }

    suspend fun getRun(runId: String): RunManifestDto = request {
        ktorRunManagement.get(runId)
    }

    override suspend fun getWorldMemory(runId: String): WorldMemoryDto = request {
        ktorWorldMemory.get(runId)
    }

    suspend fun saveWorldFact(runId: String, factId: String, requestBody: SaveWorldFactRequest): WorldFactDto = request {
        ktorWorldMemory.save(runId, factId, requestBody)
    }

    suspend fun deleteWorldFact(runId: String, factId: String): DeleteStatusDto = request {
        ktorWorldMemory.delete(runId, factId)
    }

    suspend fun deleteRun(runId: String): DeleteRunResponse = request {
        ktorRunManagement.delete(runId).also {
            appPreferences.forgetRun(runId)
        }
    }

    suspend fun refreshRun(runId: String): RunManifestDto = request {
        ktorRunOps.refreshRun(runId)
    }

    suspend fun stopRun(runId: String): RunManifestDto = request {
        ktorRunManagement.stop(runId)
    }

    suspend fun redistill(runId: String, characters: List<String>): RunManifestDto = request {
        ktorRunOps.redistill(runId, RestartRunRequest(characters = characters))
    }

    suspend fun createCrossoverSpace(
        title: String,
        worldSetting: String,
        participants: List<CrossoverParticipantRequest>,
    ): RunManifestDto = request {
        val run = ktorRunOps.createCrossoverSpace(CreateCrossoverSpaceRequest(title, worldSetting, participants))
        appPreferences.rememberRun(run.runId)
        run
    }

    suspend fun resumeDistill(runId: String): RunManifestDto = request {
        ktorRunOps.resumeDistill(runId)
    }

    suspend fun redistill(
        runId: String,
        characters: List<String>,
        novelName: String,
        novelBytes: ByteArray?,
        maxSentences: Int,
        maxChars: Int,
    ): RunManifestDto {
        val payload = withContext(Dispatchers.Default) {
            RestartRunRequest(
                characters = characters,
                novelName = novelName.takeIf { novelBytes != null }.orEmpty(),
                novelContentBase64 = novelBytes
                    ?.let { base64Encode(it) }
                    .orEmpty(),
                maxSentences = maxSentences,
                maxChars = maxChars,
            )
        }
        return request { ktorRunOps.redistill(runId, payload) }
    }

    override suspend fun suggestRedistillSegments(
        runId: String,
        character: String,
        maxSegments: Int,
    ): RedistillSuggestionsDto = request {
        ktorRunOps.suggestRedistill(
            runId,
            SuggestRedistillSegmentsRequest(character = character, maxSegments = maxSegments),
        )
    }

    suspend fun exportRun(
        runId: String,
        cacheDirectory: Path,
        includeDialogue: Boolean = true,
    ): ExportedRunPackage = request {
        val response = ktorRunOps.exportRun(runId, includeDialogue)
        if (response.status.value !in 200..299) {
            throw ApiRequestException(errorDetail(response.bodyAsText(), response.status.value))
        }
        val disposition = response.headers["Content-Disposition"].orEmpty()
        val filename = parseFilename(disposition).ifBlank { "$runId.zaomeng-run.zip" }
        val streamed = streamChannelToTempFile(response.bodyAsChannel(), cacheDirectory)
        ExportedRunPackage(
            filename = filename,
            file = streamed.file,
            byteCount = streamed.byteCount,
        )
    }

    suspend fun uploadPersonaAvatar(
        runId: String,
        character: String,
        bytes: ByteArray,
    ): PersonaAvatarDto = request {
        require(bytes.isNotEmpty()) { "头像文件为空。" }
        ktorPersona.uploadAvatar(runId, character, bytes).also { avatar ->
            avatarCache.keys.removeAll { it.startsWith("$runId|$character|") }
        }
    }

    override suspend fun getPersonaAvatar(
        runId: String,
        character: String,
        version: String,
    ): ByteArray? {
        if (version.isBlank()) return null
        val key = "$runId|$character|$version"
        avatarCache[key]?.let { return it }
        return request { ktorPersona.getAvatar(runId, character)?.also { avatarCache[key] = it } }
    }

    suspend fun listChapters(runId: String): List<ChapterDto> = request {
        ktorChapters.list(runId).items
    }

    suspend fun searchRunContent(runId: String, query: String): List<SearchResultDto> = request {
        ktorChapters.search(runId, query)
    }

    suspend fun askBookQuestion(runId: String, question: String): AskBookResponseDto = request {
        ktorChapters.ask(runId, question)
    }

    suspend fun saveChapter(
        runId: String,
        chapterId: String = "",
        payload: SaveChapterRequest,
    ): ChapterDto = request {
        ktorChapters.save(runId, chapterId, payload)
    }

    suspend fun archiveSessionAsChapter(runId: String, sessionId: String, title: String = ""): ChapterDto = request {
        ktorChapters.archiveSession(runId, ArchiveDialogueChapterRequest(sessionId, title))
    }

    suspend fun convertSessionAsNovel(runId: String, sessionId: String, title: String = ""): ChapterDto = request {
        ktorChapters.convertSessionAsNovel(runId, ArchiveDialogueChapterRequest(sessionId, title))
    }

    suspend fun deleteChapter(runId: String, chapterId: String) = request {
        ktorChapters.delete(runId, chapterId)
    }

    suspend fun continueChapter(runId: String, chapterId: String): DialogueSessionDto = request {
        ktorChapters.continueWriting(runId, chapterId)
    }

    suspend fun syncChapterSession(runId: String, chapterId: String): ChapterDto = request {
        ktorChapters.syncSession(runId, chapterId)
    }

    suspend fun reorderChapter(runId: String, chapterId: String, targetOrder: Int): List<ChapterDto> = request {
        ktorChapters.reorder(runId, chapterId, targetOrder)
    }

    suspend fun exportChapters(
        runId: String,
        format: String,
        cacheDirectory: Path,
    ): ExportedChapterManuscript = request {
        val response = ktorChapters.export(runId, format)
        if (response.status.value !in 200..299) {
            throw ApiRequestException(errorDetail(response.bodyAsText(), response.status.value))
        }
        val normalizedFormat = if (format == "text") "text" else "markdown"
        val streamed = streamChannelToTempFile(
            response.bodyAsChannel(),
            cacheDirectory,
            prefix = "zaomeng-manuscript-",
            suffix = if (normalizedFormat == "text") ".txt" else ".md",
        )
        ExportedChapterManuscript(
            filename = "$runId-manuscript.${if (normalizedFormat == "text") "txt" else "md"}",
            file = streamed.file,
        )
    }

    suspend fun getPersona(runId: String, character: String): PersonaReviewDto = request {
        ktorPersona.getReview(runId, character)
    }

    suspend fun savePersona(
        runId: String,
        character: String,
        completeFields: Map<String, String>,
        reviewNote: String,
    ): PersonaReviewDto = request {
        val payload = buildJsonObject {
            completeFields.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            put("review_source", JsonPrimitive("android"))
            put("review_note", JsonPrimitive(reviewNote))
        }
        ktorPersona.saveReview(runId, character, payload)
    }

    override suspend fun getPersonaQuality(runId: String, character: String): PersonaQualityReportDto = request {
        ktorPersona.getQuality(runId, character)
    }

    suspend fun getPersonaRepairProposal(runId: String, character: String): PersonaRepairProposalDto = request {
        ktorPersona.getRepairProposal(runId, character)
    }

    suspend fun searchOriginalKnowledge(
        runId: String,
        query: String,
        participants: List<String>,
        pinnedOnly: Boolean,
    ): List<OriginalKnowledgeEntryDto> = request {
        ktorOriginalKnowledge.search(runId, query, participants, pinnedOnly).items
    }

    suspend fun updateOriginalKnowledgeBoundary(
        runId: String,
        entryId: String,
        visibility: String,
        knowers: List<String>,
    ) = request {
        ktorOriginalKnowledge.updateBoundary(runId, entryId, visibility, knowers)
    }

    suspend fun updateOriginalKnowledgePinned(runId: String, entryId: String, pinned: Boolean) = request {
        ktorOriginalKnowledge.updatePinned(runId, entryId, pinned)
    }

    suspend fun rebuildOriginalKnowledge(runId: String) = request {
        ktorOriginalKnowledge.rebuild(runId)
    }

    suspend fun suggestPersonaField(
        runId: String,
        character: String,
        field: String,
    ): SuggestPersonaFieldResponse = request {
        ktorPersona.suggestField(runId, character, field)
    }

    override suspend fun getRelations(runId: String): RelationDetailsDto = request {
        ktorRelations.get(runId)
    }

    suspend fun updateRelation(runId: String, relation: RelationItemDto): RelationDetailsDto = request {
        ktorRelations.update(
            runId,
            relation.pairKey,
            UpdateRelationDetailRequest(
                trust = relation.trust.coerceIn(0, 10),
                affection = relation.affection.coerceIn(0, 10),
                hostility = relation.hostility.coerceIn(0, 10),
                ambiguity = relation.ambiguity.coerceIn(0, 10),
                relationshipType = relation.relationshipType,
                relationChange = relation.relationChange,
                conflictPoint = relation.conflictPoint,
                typicalInteraction = relation.typicalInteraction,
            ),
        )
    }

    suspend fun listReusableCards(kind: ReusableCardKind): List<ReusableCardDto> = request {
        ktorCards.list(kindToSegment(kind))
    }

    suspend fun getReusableCard(kind: ReusableCardKind, cardId: String): ReusableCardDto = request {
        ktorCards.get(kindToSegment(kind), cardId)
    }

    suspend fun saveReusableCard(
        kind: ReusableCardKind,
        cardId: String,
        fields: JsonObject,
    ): ReusableCardDto = request {
        ktorCards.save(kindToSegment(kind), cardId, fields)
    }

    suspend fun deleteReusableCard(kind: ReusableCardKind, cardId: String) = request {
        ktorCards.delete(kindToSegment(kind), cardId)
    }

    suspend fun generateReusableCard(kind: ReusableCardKind): ReusableCardDto = request {
        when (kind) {
            ReusableCardKind.Scene -> ktorCards.generate("scene")
            ReusableCardKind.Self -> ktorCards.generate("self")
            ReusableCardKind.Opening -> throw ApiRequestException("开场预设需要先选择人物和卡片后保存。")
        }
    }

    suspend fun recommendSceneCard(mode: String, participants: List<String>): String = request {
        val response = ktorCards.recommend(mode, participants)
        response["recommended_card_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    /** 全量读取会话列表（聊天页会话切换/章节归档等需要完整列表）；内部按分页接口循环取完。 */
    override suspend fun listSessions(runId: String?): List<DialogueSessionDto> = request {
        val items = mutableListOf<DialogueSessionDto>()
        var offset = 0
        val pageSize = 200
        while (true) {
            val page = if (runId.isNullOrBlank()) {
                ktorSessions.listRecent(offset = offset, limit = pageSize)
            } else {
                ktorSessions.listForRun(runId, offset = offset, limit = pageSize)
            }
            items += page.items.map { it.toDialogueSessionDto() }
            if (!page.hasMore || page.items.isEmpty()) break
            offset += page.items.size
        }
        items
    }

    /** 分页读取会话列表（Paging 3 数据源）。 */
    suspend fun listSessionsPage(
        runId: String? = null,
        offset: Int = 0,
        limit: Int = 50,
        query: String = "",
        sort: String = "recent",
    ): SessionsResponse = request {
        if (runId.isNullOrBlank()) {
            ktorSessions.listRecent(offset = offset, limit = limit, query = query, sort = sort)
        } else {
            ktorSessions.listForRun(runId, offset = offset, limit = limit, query = query, sort = sort)
        }
    }

    suspend fun createSession(
        runId: String,
        mode: String,
        participants: List<String>,
        controlledCharacter: String = "",
        selfName: String = "",
        selfIdentity: String = "",
        selfStyle: String = "",
        sceneCardId: String = "",
        sceneProfile: JsonObject = JsonObject(emptyMap()),
        selfCardId: String = "",
        selfCardProfile: JsonObject = JsonObject(emptyMap()),
    ): DialogueSessionDto = request {
        val inlineSelfProfile = buildJsonObject {
            if (selfName.isNotBlank()) put("display_name", JsonPrimitive(selfName))
            if (selfIdentity.isNotBlank()) put("scene_identity", JsonPrimitive(selfIdentity))
            if (selfStyle.isNotBlank()) put("interaction_style", JsonPrimitive(selfStyle))
        }
        val selfProfile = buildJsonObject {
            selfCardProfile.forEach { (key, value) -> put(key, value) }
            inlineSelfProfile.forEach { (key, value) -> put(key, value) }
        }
        val payload = CreateDialogueSessionRequest(
                mode = mode,
                participants = participants,
                controlledCharacter = controlledCharacter.takeIf { mode == "act" }.orEmpty(),
                sceneCardId = sceneCardId,
                sceneProfile = sceneProfile,
                selfCardId = selfCardId,
                selfProfile = selfProfile,
            )
        val session = ktorSessions.create(runId, payload)
        appPreferences.rememberSession(runId, session.sessionId)
        session
    }

    override suspend fun getSession(
        runId: String,
        sessionId: String,
        includeTranscript: Boolean,
    ): DialogueSessionDto = request {
        ktorSessions.get(runId, sessionId, includeTranscript = includeTranscript)
    }

    /** 会话消息分页：order=desc 时 offset 表示跳过最新 N 条，返回更早一页（新→旧）。 */
    override suspend fun listSessionMessages(
        runId: String,
        sessionId: String,
        offset: Int,
        limit: Int,
        order: String,
    ): MessagesResponse = request {
        ktorSessions.listMessages(runId, sessionId, offset, limit, order)
    }

    suspend fun updateSessionTitle(
        runId: String,
        sessionId: String,
        title: String,
    ): DialogueSessionDto = request {
        ktorSessions.updateTitle(runId, sessionId, title)
    }

    suspend fun searchSession(
        runId: String,
        sessionId: String,
        query: String,
        limit: Int = 50,
    ): List<ChatSearchResultDto> = request {
        ktorDialogue.searchSession(
            runId = runId,
            sessionId = sessionId,
            query = query.trim(),
            limit = limit.coerceIn(1, 100),
        )
    }

    override suspend fun recoverSession(
        runId: String,
        sessionId: String,
        force: Boolean,
    ): DialogueSessionDto = request {
        ktorDialogue.recoverSession(runId, sessionId, force)
    }

    fun streamReply(
        runId: String,
        sessionId: String,
        message: String,
        messageKind: String,
        operationId: String,
        suppressTranscriptMessage: Boolean = messageKind == "plot",
        includeInnerThoughts: Boolean = false,
        includeModelReasoning: Boolean = false,
        includeTranscript: Boolean = false,
    ): Flow<DialogueStreamEvent> = flow {
        try {
            val source = ktorDialogue.streamReply(
                runId = runId,
                sessionId = sessionId,
                payload = DialogueReplyRequest(
                    message = message,
                    messageKind = messageKind,
                    suppressTranscriptMessage = suppressTranscriptMessage,
                    includeInnerThoughts = includeInnerThoughts,
                    includeModelReasoning = includeModelReasoning,
                    includeTranscript = includeTranscript,
                    operationId = operationId,
                ),
            )
            try {
                var eventName = "message"
                val dataLines = mutableListOf<String>()
                var terminalReceived = false

                while (!terminalReceived) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() && dataLines.isNotEmpty() -> {
                            val event = parseDialogueStreamEvent(
                                eventName,
                                dataLines.joinToString("\n"),
                            )
                            dataLines.clear()
                            eventName = "message"
                            if (event != null) {
                                emit(event)
                                terminalReceived = event is DialogueStreamEvent.Complete ||
                                    event is DialogueStreamEvent.Failure
                            }
                        }
                        line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                        line.startsWith("data:") -> dataLines += line.substringAfter(':').trimStart()
                        line.startsWith(":") -> Unit
                    }
                }
                if (!terminalReceived && dataLines.isNotEmpty()) {
                    val event = parseDialogueStreamEvent(
                        eventName,
                        dataLines.joinToString("\n"),
                    )
                    if (event != null) {
                        emit(event)
                        terminalReceived = event is DialogueStreamEvent.Complete ||
                            event is DialogueStreamEvent.Failure
                    }
                }
                if (!terminalReceived) {
                    throw ApiRequestException("流式连接提前结束，可安全重试这次发送。")
                }
            } finally {
                source.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiRequestException) {
            throw error
        } catch (error: Throwable) {
            val message = generateSequence(error) { it.cause }
                .mapNotNull { it.message?.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: "流式连接失败。"
            throw ApiRequestException(message, error)
        }
    }.flowOn(platformIoDispatcher)

    private fun parseDialogueStreamEvent(
        eventName: String,
        data: String,
    ): DialogueStreamEvent? {
        val payload = runCatching { json.parseToJsonElement(data).jsonObject }.getOrElse {
            throw ApiRequestException("无法解析流式回复。", it)
        }
        return when (eventName.ifBlank { payload["event"]?.jsonPrimitive?.contentOrNull.orEmpty() }) {
            "status" -> DialogueStreamEvent.Status(
                phase = payload["phase"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                message = payload["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            "delta" -> DialogueStreamEvent.Delta(
                index = payload["index"]?.jsonPrimitive?.intOrNull ?: 0,
                speaker = payload["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                role = payload["role"]?.jsonPrimitive?.contentOrNull ?: "character",
                text = payload["text"]?.jsonPrimitive?.contentOrNull
                    ?: payload["delta"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                field = payload["field"]?.jsonPrimitive?.contentOrNull ?: "message",
            )
            "reset" -> DialogueStreamEvent.Reset(
                message = payload["message"]?.jsonPrimitive?.contentOrNull
                    ?: "正在重新整理回复…",
            )
            "complete" -> payload["session"]?.let { session ->
                val decodedSession = runCatching { json.decodeFromJsonElement<DialogueSessionDto>(session) }
                    .getOrElse { error ->
                        PlatformLog.e(TAG, "Failed to decode stream complete session. Session JSON: $session", error)
                        throw error
                    }
                DialogueStreamEvent.Complete(
                    session = decodedSession,
                    replayed = payload["replayed"]?.jsonPrimitive?.booleanOrNull ?: false,
                    appendedTranscript = payload["appended_transcript"]
                        ?.let { element ->
                            runCatching {
                                json.decodeFromJsonElement<List<TranscriptItemDto>>(element)
                            }.getOrElse { error ->
                                PlatformLog.e(TAG, "Failed to decode appended transcript", error)
                                emptyList()
                            }
                        }
                        .orEmpty(),
                    transcriptCount = payload["transcript_count"]?.jsonPrimitive?.intOrNull
                        ?: decodedSession.transcriptCount,
                )
            }
            "error" -> DialogueStreamEvent.Failure(
                message = payload["message"]?.jsonPrimitive?.contentOrNull
                    ?: payload["detail"]?.jsonPrimitive?.contentOrNull
                    ?: "回复生成失败。",
                retryable = payload["retryable"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
            else -> null
        }
    }

    suspend fun suggestReply(
        runId: String,
        sessionId: String,
        seedText: String = "",
        direction: String = "",
    ): String = request {
        ktorDialogue.suggestReply(runId, sessionId, seedText, direction)
    }

    suspend fun correctLatestReply(runId: String, sessionId: String): DialogueSessionDto = request {
        ktorDialogue.correctLatest(runId, sessionId)
    }

    suspend fun deepReviewLatestReply(runId: String, sessionId: String): DialogueSessionDto = request {
        ktorDialogue.deepReview(runId, sessionId)
    }

    suspend fun dialogueDirectorOptions(
        runId: String,
        sessionId: String,
        goal: String,
        action: String = "advance",
    ): JsonObject = request {
        ktorDialogue.directorOptions(runId, sessionId, goal, action)
    }

    suspend fun branchDialogueTurn(
        runId: String,
        sessionId: String,
        turnId: String,
    ): DialogueSessionDto = request {
        ktorDialogue.branchFromTurn(runId, sessionId, turnId)
    }

    suspend fun branchDialogueScene(
        runId: String,
        sessionId: String,
        sceneIndex: Int,
    ): DialogueSessionDto = request {
        ktorDialogue.branchFromScene(runId, sessionId, sceneIndex)
    }

    suspend fun updateDialogueBranchMeta(
        runId: String,
        sessionId: String,
        label: String? = null,
        isMainline: Boolean? = null,
        lockedEventIds: List<String>? = null,
    ): DialogueSessionDto = request {
        ktorDialogue.updateBranchMeta(runId, sessionId, label, isMainline, lockedEventIds)
    }

    suspend fun setDialogueRelationLock(
        runId: String,
        sessionId: String,
        pairKey: String,
        locked: Boolean,
    ): DialogueSessionDto = request {
        ktorDialogue.setRelationLock(runId, sessionId, pairKey, locked)
    }

    suspend fun switchDialogueScene(
        runId: String,
        sessionId: String,
        sceneCardId: String,
        transitionMessage: String,
        autoContinue: Boolean,
    ): DialogueSessionDto = request {
        ktorDialogue.switchScene(runId, sessionId, sceneCardId, transitionMessage, autoContinue)
    }

    suspend fun recommendDialogueScene(runId: String, sessionId: String): JsonObject = request {
        ktorDialogue.recommendScene(runId, sessionId)
    }

    suspend fun saveDialogueMemory(
        runId: String,
        sessionId: String,
        memory: DialogueMemoryDto,
    ): DialogueSessionDto = request {
        val payload = UpsertDialogueMemoryRequest(
            text = memory.text,
            category = memory.category,
            pinned = memory.pinned,
            enabled = memory.enabled,
        )
        if (memory.memoryId.isBlank()) {
            ktorDialogue.saveMemory(runId, sessionId, "", memory.text, memory.category, memory.pinned, memory.enabled)
        } else {
            ktorDialogue.saveMemory(runId, sessionId, memory.memoryId, memory.text, memory.category, memory.pinned, memory.enabled)
        }
    }

    suspend fun deleteDialogueMemory(
        runId: String,
        sessionId: String,
        memoryId: String,
    ): DialogueSessionDto = request {
        ktorDialogue.deleteMemory(runId, sessionId, memoryId)
    }

    override suspend fun getDialogueMemoryQuality(
        runId: String,
        sessionId: String,
    ): MemoryQualityReportDto = request {
        ktorDialogue.getMemoryQuality(runId, sessionId)
    }

    suspend fun updateAutomaticMemoryStatus(runId: String, sessionId: String, memoryId: String, status: String) = request {
        ktorDialogue.updateAutomaticMemoryStatus(runId, sessionId, memoryId, status)
    }

    suspend fun mergeDuplicateDialogueMemories(runId: String, sessionId: String) = request {
        ktorDialogue.mergeDuplicateMemories(runId, sessionId)
    }

    suspend fun deleteSession(runId: String, sessionId: String) = request {
        ktorSessions.delete(runId, sessionId).also {
            appPreferences.forgetSession(runId, sessionId)
        }
    }

    suspend fun deleteSessions(items: List<SessionRefDto>): DeleteSessionsResponse = request {
        ktorSessions.deleteBatch(DeleteSessionsRequest(items)).also { response ->
            (response.deleted + response.notFound).forEach { session ->
                appPreferences.forgetSession(session.runId, session.sessionId)
            }
        }
    }

    private fun kindToSegment(kind: ReusableCardKind): String = when (kind) {
        ReusableCardKind.Scene -> "scene"
        ReusableCardKind.Self -> "self"
        ReusableCardKind.Opening -> "opening"
    }

    private suspend fun <T> request(block: suspend () -> T): T = withContext(platformIoDispatcher) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiRequestException) {
            throw error
        } catch (error: Throwable) {
            PlatformLog.e(TAG, "Repository request failed", error)
            val readable = generateSequence(error) { it.cause }
                .mapNotNull { it.message?.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: "请求失败，请稍后重试。"
            throw ApiRequestException(readable, error)
        }
    }

    private fun errorDetail(body: String?, status: Int): String {
        val detail = runCatching {
            json.parseToJsonElement(body.orEmpty()).jsonObject["detail"]
        }.getOrNull()
        val message = when (detail) {
            is JsonPrimitive -> detail.contentOrNull.orEmpty()
            is JsonArray -> detail.mapNotNull { item ->
                val issue = item as? JsonObject ?: return@mapNotNull null
                val location = (issue["loc"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.dropWhile { it == "body" }
                    ?.joinToString(".")
                    .orEmpty()
                val issueMessage = (issue["msg"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                when {
                    issueMessage.isBlank() -> null
                    location.isBlank() -> issueMessage
                    else -> "$location: $issueMessage"
                }
            }.take(3).joinToString("；")
            is JsonObject -> (detail["message"] as? JsonPrimitive)?.contentOrNull
                ?: (detail["detail"] as? JsonPrimitive)?.contentOrNull
                ?: ""
            else -> ""
        }
        return message.takeIf(String::isNotBlank) ?: "本地接口返回 HTTP $status。"
    }

    private fun parseFilename(contentDisposition: String): String {
        val encoded = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.getOrNull(1)
        if (!encoded.isNullOrBlank()) {
            return percentDecodeUtf8(encoded.replace("+", "%2B"))
        }
        return Regex("filename=\\\"?([^;\\\"]+)", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.getOrNull(1).orEmpty()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val TAG = "ZaomengRepository"
    }
}

/** UTF-8 百分号解码（等价 java.net.URLDecoder 的 UTF-8 行为，KMP 可用）。 */
private fun percentDecodeUtf8(input: String): String {
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < input.length) {
        val char = input[index]
        if (char == '%' && index + 2 < input.length) {
            val high = input[index + 1].digitToIntOrNull(16)
            val low = input[index + 2].digitToIntOrNull(16)
            if (high != null && low != null) {
                bytes.add(((high shl 4) or low).toByte())
                index += 3
                continue
            }
        }
        char.toString().encodeToByteArray().let { bytes.addAll(it.toList()) }
        index += 1
    }
    return bytes.toByteArray().decodeToString()
}

class ApiRequestException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
) : Exception(message, cause)

enum class ReusableCardKind {
    Scene,
    Self,
    Opening,
}

package top.wkbin.zaomeng.data

import android.util.Base64
import java.io.File
import java.io.OutputStream
import top.wkbin.zaomeng.backend.BackendState
import top.wkbin.zaomeng.backend.EmbeddedBackendController
import top.wkbin.zaomeng.backend.ModelApiKeyStore
import top.wkbin.zaomeng.data.api.CreateDialogueSessionRequest
import top.wkbin.zaomeng.data.api.CreateRunRequest
import top.wkbin.zaomeng.data.api.CreateCrossoverSpaceRequest
import top.wkbin.zaomeng.data.api.CrossoverParticipantRequest
import top.wkbin.zaomeng.data.api.BuiltinNovelDto
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
import top.wkbin.zaomeng.data.api.DialogueReplyRequest
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.DialogueStreamEvent
import top.wkbin.zaomeng.data.api.DialogueSuggestionRequest
import top.wkbin.zaomeng.data.api.ChatSearchResultDto
import top.wkbin.zaomeng.data.api.ExportedRunPackage
import top.wkbin.zaomeng.data.api.ExportedChapterManuscript
import top.wkbin.zaomeng.data.api.ImportRunPackageRequest
import top.wkbin.zaomeng.data.api.LibraryPackageImportDto
import top.wkbin.zaomeng.data.api.EstimateSamplingRequest
import top.wkbin.zaomeng.data.api.ModelSettingsDto
import top.wkbin.zaomeng.data.api.SamplingPlanDto
import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
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
import top.wkbin.zaomeng.data.api.SaveChapterRequest
import top.wkbin.zaomeng.data.api.SearchResultDto
import top.wkbin.zaomeng.data.api.SessionRefDto
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class ZaomengRepository(
    private val backend: EmbeddedBackendController,
    private val appPreferences: AppPreferencesRepository,
    private val modelApiKeyStore: ModelApiKeyStore,
) {
    private val avatarCache = mutableMapOf<String, ByteArray>()
    val backendState: StateFlow<BackendState> = backend.state
    val preferences: Flow<AppPreferences> = appPreferences.preferences

    fun startBackend() = backend.start()
    fun retryBackend() = backend.retry()

    suspend fun getModelSettings(): ModelSettingsDto = request {
        backend.requireApi().getModelSettings()
    }

    suspend fun saveModelSettings(request: SaveModelSettingsRequest): ModelSettingsDto = request {
        backend.requireApi().saveModelSettings(request).also { saved ->
            modelApiKeyStore.saveForProfile(request.profileId.ifBlank { saved.activeProfileId }, request.apiKey)
        }
    }

    suspend fun testModelSettings(request: TestModelSettingsRequest) = request {
        backend.requireApi().testModelSettings(request)
    }

    suspend fun activateModelProfile(profileId: String): ModelSettingsDto = request {
        backend.requireApi().activateModelProfile(profileId)
    }

    suspend fun deleteModelProfile(profileId: String): ModelSettingsDto = request {
        backend.requireApi().deleteModelProfile(profileId).also {
            modelApiKeyStore.deleteForProfile(profileId)
        }
    }

    suspend fun listPlugins(): List<PluginDto> = request {
        backend.requireApi().listPlugins().items
    }

    suspend fun refreshPlugins(): List<PluginDto> = request {
        backend.requireApi().refreshPlugins().items
    }

    suspend fun inspectPluginPackage(
        filename: String,
        contentBase64: String,
    ): PluginPackageInspectionDto = request {
        backend.requireApi().inspectPluginPackage(
            InspectPluginPackageRequest(filename, contentBase64),
        )
    }

    suspend fun installPluginPackage(
        token: String,
        allowUpdate: Boolean,
    ): PluginDto = request {
        backend.requireApi().installPluginPackage(
            token,
            InstallPluginPackageRequest(
                confirmPermissions = true,
                allowUpdate = allowUpdate,
            ),
        )
    }

    suspend fun enablePlugin(pluginId: String): PluginDto = request {
        backend.requireApi().enablePlugin(pluginId)
    }

    suspend fun disablePlugin(pluginId: String): PluginDto = request {
        backend.requireApi().disablePlugin(pluginId)
    }

    suspend fun uninstallPlugin(pluginId: String): UninstallPluginResponse = request {
        backend.requireApi().uninstallPlugin(pluginId)
    }

    suspend fun listPluginLogs(pluginId: String): List<PluginLogDto> = request {
        backend.requireApi().listPluginLogs(pluginId).items
    }

    suspend fun updatePluginConfig(
        pluginId: String,
        config: kotlinx.serialization.json.JsonObject,
    ): PluginConfigResponse = request {
        backend.requireApi().updatePluginConfig(
            pluginId,
            UpdatePluginConfigRequest(config),
        )
    }

    suspend fun invokePluginChatAction(
        runId: String,
        sessionId: String,
        pluginId: String,
        actionId: String,
        seedText: String = "",
        direction: String = "",
    ): PluginChatActionResponse = request {
        val result = backend.requireApi().invokePluginChatAction(
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
        backend.requireApi().invokePluginTemporaryNpcGenerator(
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
        backend.requireApi().setGenerationEnhancerState(
            runId,
            sessionId,
            pluginId,
            enhancerId,
            SetGenerationEnhancerStateRequest(enabled),
        )
    }

    suspend fun exportDiagnostics(destination: OutputStream): Long = request {
        val response = backend.requireApi().exportDiagnostics()
        if (!response.isSuccessful) {
            throw ApiRequestException(errorDetail(response.errorBody()?.string(), response.code()))
        }
        val body = response.body() ?: throw ApiRequestException("诊断信息为空。")
        body.use { source ->
            source.byteStream().buffered().use { input -> input.copyTo(destination) }
        }
    }

    suspend fun listRuns(): List<RunManifestDto> = request {
        backend.requireApi().listRuns().items
    }

    suspend fun listBuiltinNovels(): List<BuiltinNovelDto> = request {
        backend.requireApi().listBuiltinNovels().items
    }

    suspend fun cloneBuiltinNovel(packageId: String): RunManifestDto = request {
        val run = backend.requireApi().cloneBuiltinNovel(packageId)
        appPreferences.rememberRun(run.runId)
        run
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
                novelContentBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                characters = characters,
                maxSentences = maxSentences,
                maxChars = maxChars,
                autoRun = autoRun,
                deferRun = !autoRun,
            )
        }
        return request {
            val run = backend.requireApi().createRun(
                payload,
            )
            appPreferences.rememberRun(run.runId)
            run
        }
    }

    suspend fun estimateSampling(
        charCount: Int,
        sentenceCount: Int,
        characterCount: Int,
        maxSentences: Int,
        maxChars: Int,
    ): SamplingPlanDto = request {
        backend.requireApi().estimateSampling(
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
                contentBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                libraryPackage = libraryPackage,
            )
        }
        return request {
            val run = backend.requireApi().importRun(
                payload,
            )
            appPreferences.rememberRun(run.runId)
            run
        }
    }

    suspend fun saveImportDefaults(characters: String, autoDistill: Boolean = true) {
        appPreferences.saveImportDefaults(characters, autoDistill)
    }

    suspend fun rememberRunLocation(runId: String) {
        appPreferences.rememberRun(runId)
    }

    suspend fun rememberSessionLocation(runId: String, sessionId: String) {
        appPreferences.rememberSession(runId, sessionId)
    }

    suspend fun clearLastSessionLocation() {
        appPreferences.clearLastSession()
    }

    suspend fun clearLastLocation() {
        appPreferences.clearLastLocation()
    }

    suspend fun getRun(runId: String): RunManifestDto = request {
        backend.requireApi().getRun(runId)
    }

    suspend fun getWorldMemory(runId: String): WorldMemoryDto = request {
        backend.requireApi().getWorldMemory(runId)
    }

    suspend fun saveWorldFact(runId: String, factId: String, requestBody: SaveWorldFactRequest): WorldFactDto = request {
        if (factId.isBlank()) backend.requireApi().createWorldFact(runId, requestBody)
        else backend.requireApi().updateWorldFact(runId, factId, requestBody)
    }

    suspend fun deleteWorldFact(runId: String, factId: String): DeleteStatusDto = request {
        backend.requireApi().deleteWorldFact(runId, factId)
    }

    suspend fun deleteRun(runId: String): DeleteRunResponse = request {
        backend.requireApi().deleteRun(runId).also {
            appPreferences.forgetRun(runId)
        }
    }

    suspend fun refreshRun(runId: String): RunManifestDto = request {
        backend.requireApi().refreshRun(runId)
    }

    suspend fun stopRun(runId: String): RunManifestDto = request {
        backend.requireApi().stopRun(runId)
    }

    suspend fun redistill(runId: String, characters: List<String>): RunManifestDto = request {
        backend.requireApi().redistillRun(runId, RestartRunRequest(characters = characters))
    }

    suspend fun createCrossoverSpace(
        title: String,
        worldSetting: String,
        participants: List<CrossoverParticipantRequest>,
    ): RunManifestDto = request {
        val run = backend.requireApi().createCrossoverSpace(
            CreateCrossoverSpaceRequest(title, worldSetting, participants),
        )
        appPreferences.rememberRun(run.runId)
        run
    }

    suspend fun resumeDistill(runId: String): RunManifestDto = request {
        backend.requireApi().resumeDistillRun(runId)
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
                    ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                    .orEmpty(),
                maxSentences = maxSentences,
                maxChars = maxChars,
            )
        }
        return request { backend.requireApi().redistillRun(runId, payload) }
    }

    suspend fun suggestRedistillSegments(
        runId: String,
        character: String,
        maxSegments: Int = 3,
    ): RedistillSuggestionsDto = request {
        backend.requireApi().suggestRedistillSegments(
            runId,
            SuggestRedistillSegmentsRequest(character = character, maxSegments = maxSegments),
        )
    }

    suspend fun exportRun(
        runId: String,
        cacheDirectory: File,
        includeDialogue: Boolean = true,
    ): ExportedRunPackage = request {
        val response = backend.requireApi().exportRun(runId, includeDialogue)
        if (!response.isSuccessful) {
            throw ApiRequestException(errorDetail(response.errorBody()?.string(), response.code()))
        }
        val body = response.body() ?: throw ApiRequestException("导出内容为空。")
        val disposition = response.headers()["Content-Disposition"].orEmpty()
        val filename = parseFilename(disposition).ifBlank { "$runId.zaomeng-run.zip" }
        val streamed = body.use {
            streamToTempFile(it.byteStream(), cacheDirectory)
        }
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
        val part = MultipartBody.Part.createFormData(
            "file",
            "avatar.png",
            bytes.toRequestBody("image/png".toMediaType()),
        )
        backend.requireApi().uploadPersonaAvatar(runId, character, part).also { avatar ->
            avatarCache.keys.removeAll { it.startsWith("$runId|$character|") }
        }
    }

    suspend fun getPersonaAvatar(
        runId: String,
        character: String,
        version: String,
    ): ByteArray? {
        if (version.isBlank()) return null
        val key = "$runId|$character|$version"
        avatarCache[key]?.let { return it }
        return request {
            val response = backend.requireApi().getPersonaAvatar(runId, character)
            if (response.code() == 404) return@request null
            if (!response.isSuccessful) {
                throw ApiRequestException(errorDetail(response.errorBody()?.string(), response.code()))
            }
            response.body()?.use { it.bytes() }?.also { avatarCache[key] = it }
        }
    }

    suspend fun listChapters(runId: String): List<ChapterDto> = request {
        backend.requireApi().listChapters(runId).items
    }

    suspend fun searchRunContent(runId: String, query: String): List<SearchResultDto> = request {
        backend.requireApi().searchRunContent(runId, query).items
    }

    suspend fun askBookQuestion(runId: String, question: String): AskBookResponseDto = request {
        backend.requireApi().askBookQuestion(runId, AskBookQuestionRequest(question))
    }

    suspend fun saveChapter(
        runId: String,
        chapterId: String = "",
        payload: SaveChapterRequest,
    ): ChapterDto = request {
        if (chapterId.isBlank()) {
            backend.requireApi().createChapter(runId, payload)
        } else {
            backend.requireApi().updateChapter(runId, chapterId, payload)
        }
    }

    suspend fun archiveSessionAsChapter(runId: String, sessionId: String, title: String = ""): ChapterDto = request {
        backend.requireApi().archiveSessionAsChapter(runId, ArchiveDialogueChapterRequest(sessionId, title))
    }

    suspend fun convertSessionAsNovel(runId: String, sessionId: String, title: String = ""): ChapterDto = request {
        backend.requireApi().convertSessionAsNovel(runId, ArchiveDialogueChapterRequest(sessionId, title))
    }

    suspend fun deleteChapter(runId: String, chapterId: String) = request {
        backend.requireApi().deleteChapter(runId, chapterId)
    }

    suspend fun continueChapter(runId: String, chapterId: String): DialogueSessionDto = request {
        backend.requireApi().continueChapter(runId, chapterId)
    }

    suspend fun syncChapterSession(runId: String, chapterId: String): ChapterDto = request {
        backend.requireApi().syncChapterSession(runId, chapterId)
    }

    suspend fun reorderChapter(runId: String, chapterId: String, targetOrder: Int): List<ChapterDto> = request {
        backend.requireApi().reorderChapter(runId, chapterId, ReorderChapterRequest(targetOrder)).items
    }

    suspend fun exportChapters(
        runId: String,
        format: String,
        cacheDirectory: File,
    ): ExportedChapterManuscript = request {
        val response = backend.requireApi().exportChapters(runId, format)
        if (!response.isSuccessful) {
            throw ApiRequestException(errorDetail(response.errorBody()?.string(), response.code()))
        }
        val body = response.body() ?: throw ApiRequestException("章节导出内容为空。")
        val normalizedFormat = if (format == "text") "text" else "markdown"
        val streamed = body.use {
            streamToTempFile(
                it.byteStream(),
                cacheDirectory,
                prefix = "zaomeng-manuscript-",
                suffix = if (normalizedFormat == "text") ".txt" else ".md",
            )
        }
        ExportedChapterManuscript(
            filename = "$runId-manuscript.${if (normalizedFormat == "text") "txt" else "md"}",
            file = streamed.file,
        )
    }

    suspend fun getPersona(runId: String, character: String): PersonaReviewDto = request {
        backend.requireApi().getPersona(runId, character)
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
        backend.requireApi().savePersona(runId, character, payload)
    }

    suspend fun getPersonaQuality(runId: String, character: String): PersonaQualityReportDto = request {
        backend.requireApi().getPersonaQuality(runId, character)
    }

    suspend fun suggestPersonaField(
        runId: String,
        character: String,
        field: String,
    ): SuggestPersonaFieldResponse = request {
        backend.requireApi().suggestPersonaField(runId, character, SuggestPersonaFieldRequest(field))
    }

    suspend fun getRelations(runId: String): RelationDetailsDto = request {
        backend.requireApi().getRelations(runId)
    }

    suspend fun updateRelation(runId: String, relation: RelationItemDto): RelationDetailsDto = request {
        backend.requireApi().updateRelation(
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
        val api = backend.requireApi()
        when (kind) {
            ReusableCardKind.Scene -> api.listSceneCards().items
            ReusableCardKind.Self -> api.listSelfCards().items
            ReusableCardKind.Opening -> api.listOpeningPresets().items
        }
    }

    suspend fun getReusableCard(kind: ReusableCardKind, cardId: String): ReusableCardDto = request {
        val api = backend.requireApi()
        when (kind) {
            ReusableCardKind.Scene -> api.getSceneCard(cardId)
            ReusableCardKind.Self -> api.getSelfCard(cardId)
            ReusableCardKind.Opening -> api.getOpeningPreset(cardId)
        }
    }

    suspend fun saveReusableCard(
        kind: ReusableCardKind,
        cardId: String,
        fields: JsonObject,
    ): ReusableCardDto = request {
        val api = backend.requireApi()
        when (kind) {
            ReusableCardKind.Scene -> if (cardId.isBlank()) {
                api.createSceneCard(fields)
            } else {
                api.updateSceneCard(cardId, fields)
            }
            ReusableCardKind.Self -> if (cardId.isBlank()) {
                api.createSelfCard(fields)
            } else {
                api.updateSelfCard(cardId, fields)
            }
            ReusableCardKind.Opening -> if (cardId.isBlank()) {
                api.createOpeningPreset(fields)
            } else {
                api.updateOpeningPreset(cardId, fields)
            }
        }
    }

    suspend fun deleteReusableCard(kind: ReusableCardKind, cardId: String) = request {
        val api = backend.requireApi()
        when (kind) {
            ReusableCardKind.Scene -> api.deleteSceneCard(cardId)
            ReusableCardKind.Self -> api.deleteSelfCard(cardId)
            ReusableCardKind.Opening -> api.deleteOpeningPreset(cardId)
        }
    }

    suspend fun generateReusableCard(kind: ReusableCardKind): ReusableCardDto = request {
        when (kind) {
            ReusableCardKind.Scene -> backend.requireApi().generateSceneCard()
            ReusableCardKind.Self -> backend.requireApi().generateSelfCard()
            ReusableCardKind.Opening -> throw ApiRequestException("开场预设需要先选择人物和卡片后保存。")
        }
    }

    suspend fun recommendSceneCard(mode: String, participants: List<String>): String = request {
        val response = backend.requireApi().recommendSceneCards(
            RecommendSceneCardsRequest(mode = mode, participants = participants),
        )
        response["recommended_card_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    suspend fun listSessions(runId: String? = null): List<DialogueSessionDto> = request {
        val api = backend.requireApi()
        if (runId.isNullOrBlank()) api.listRecentSessions().items else api.listRunSessions(runId).items
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
        val session = backend.requireApi().createDialogueSession(
            runId,
            CreateDialogueSessionRequest(
                mode = mode,
                participants = participants,
                controlledCharacter = controlledCharacter,
                sceneCardId = sceneCardId,
                sceneProfile = sceneProfile,
                selfCardId = selfCardId,
                selfProfile = selfProfile,
            ),
        )
        appPreferences.rememberSession(runId, session.sessionId)
        session
    }

    suspend fun getSession(runId: String, sessionId: String): DialogueSessionDto = request {
        backend.requireApi().getDialogueSession(runId, sessionId)
    }

    suspend fun updateSessionTitle(
        runId: String,
        sessionId: String,
        title: String,
    ): DialogueSessionDto = request {
        backend.requireApi().updateDialogueSessionTitle(
            runId,
            sessionId,
            UpdateDialogueSessionTitleRequest(title = title),
        )
    }

    suspend fun searchSession(
        runId: String,
        sessionId: String,
        query: String,
        limit: Int = 50,
    ): List<ChatSearchResultDto> = request {
        backend.requireApi().searchDialogueSession(
            runId = runId,
            sessionId = sessionId,
            query = query.trim(),
            limit = limit.coerceIn(1, 100),
        ).items
    }

    suspend fun recoverSession(
        runId: String,
        sessionId: String,
        force: Boolean = false,
    ): DialogueSessionDto = request {
        backend.requireApi().recoverDialogueSession(runId, sessionId, force)
    }

    suspend fun reply(
        runId: String,
        sessionId: String,
        message: String,
        messageKind: String,
        includeInnerThoughts: Boolean = false,
    ): DialogueSessionDto = request {
        backend.requireApi().replyDialogue(
            runId,
            sessionId,
            DialogueReplyRequest(
                message = message,
                messageKind = messageKind,
                suppressTranscriptMessage = messageKind == "plot",
                includeInnerThoughts = includeInnerThoughts,
            ),
        )
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
    ): Flow<DialogueStreamEvent> = flow {
        try {
            val response = backend.requireApi().streamDialogueReply(
                runId = runId,
                sessionId = sessionId,
                operationId = operationId,
                request = DialogueReplyRequest(
                    message = message,
                    messageKind = messageKind,
                    suppressTranscriptMessage = suppressTranscriptMessage,
                    includeInnerThoughts = includeInnerThoughts,
                    includeModelReasoning = includeModelReasoning,
                    operationId = operationId,
                ),
            )
            if (!response.isSuccessful) {
                throw ApiRequestException(
                    errorDetail(response.errorBody()?.string(), response.code()),
                    statusCode = response.code(),
                )
            }
            val body = response.body() ?: throw ApiRequestException("流式回复内容为空。")
            var eventName = "message"
            val dataLines = mutableListOf<String>()
            var terminalReceived = false

            body.use { responseBody ->
                responseBody.charStream().buffered().use { reader ->
                    while (!terminalReceived) {
                        val line = reader.readLine() ?: break
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
                }
            }
            if (!terminalReceived) {
                throw ApiRequestException("流式连接提前结束，可安全重试这次发送。")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiRequestException) {
            throw error
        } catch (error: HttpException) {
            throw ApiRequestException(
                errorDetail(error.response()?.errorBody()?.string(), error.code()),
                error,
                statusCode = error.code(),
            )
        } catch (error: Throwable) {
            val message = generateSequence(error) { it.cause }
                .mapNotNull { it.message?.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: "流式连接失败。"
            throw ApiRequestException(message, error)
        }
    }.flowOn(Dispatchers.IO)

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
                DialogueStreamEvent.Complete(
                    session = json.decodeFromJsonElement(session),
                    replayed = payload["replayed"]?.jsonPrimitive?.booleanOrNull ?: false,
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
        backend.requireApi().suggestDialogue(
            runId,
            sessionId,
            DialogueSuggestionRequest(seedText = seedText, direction = direction),
        ).suggestion
    }

    suspend fun correctLatestReply(runId: String, sessionId: String): DialogueSessionDto = request {
        backend.requireApi().correctLatestDialogue(runId, sessionId)
    }

    suspend fun deepReviewLatestReply(runId: String, sessionId: String): DialogueSessionDto = request {
        backend.requireApi().deepReviewLatestDialogue(runId, sessionId)
    }

    suspend fun dialogueDirectorOptions(
        runId: String,
        sessionId: String,
        goal: String,
        action: String = "advance",
    ): JsonObject = request {
        backend.requireApi().directDialogue(
            runId,
            sessionId,
            DialogueDirectorRequest(goal = goal, action = action),
        )
    }

    suspend fun branchDialogueTurn(
        runId: String,
        sessionId: String,
        turnId: String,
    ): DialogueSessionDto = request {
        backend.requireApi().branchDialogueTurn(runId, sessionId, BranchDialogueTurnRequest(turnId))
    }

    suspend fun branchDialogueScene(
        runId: String,
        sessionId: String,
        sceneIndex: Int,
    ): DialogueSessionDto = request {
        backend.requireApi().branchDialogueScene(
            runId,
            sessionId,
            BranchDialogueSceneRequest(sceneIndex),
        )
    }

    suspend fun updateDialogueBranchMeta(
        runId: String,
        sessionId: String,
        label: String? = null,
        isMainline: Boolean? = null,
        lockedEventIds: List<String>? = null,
    ): DialogueSessionDto = request {
        backend.requireApi().updateDialogueBranchMeta(
            runId,
            sessionId,
            UpdateDialogueBranchMetaRequest(
                label = label,
                isMainline = isMainline,
                lockedEventIds = lockedEventIds,
            ),
        )
    }

    suspend fun setDialogueRelationLock(
        runId: String,
        sessionId: String,
        pairKey: String,
        locked: Boolean,
    ): DialogueSessionDto = request {
        backend.requireApi().updateDialogueRelationLock(
            runId,
            sessionId,
            UpdateDialogueRelationLockRequest(pairKey = pairKey, locked = locked),
        )
    }

    suspend fun switchDialogueScene(
        runId: String,
        sessionId: String,
        sceneCardId: String,
        transitionMessage: String,
        autoContinue: Boolean,
    ): DialogueSessionDto = request {
        backend.requireApi().switchDialogueScene(
            runId,
            sessionId,
            SwitchDialogueSceneRequest(
                sceneCardId = sceneCardId,
                transitionMessage = transitionMessage,
                autoContinue = autoContinue,
            ),
        )
    }

    suspend fun recommendDialogueScene(runId: String, sessionId: String): JsonObject = request {
        backend.requireApi().recommendDialogueScene(runId, sessionId)
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
            backend.requireApi().createDialogueMemory(runId, sessionId, payload)
        } else {
            backend.requireApi().updateDialogueMemory(runId, sessionId, memory.memoryId, payload)
        }
    }

    suspend fun deleteDialogueMemory(
        runId: String,
        sessionId: String,
        memoryId: String,
    ): DialogueSessionDto = request {
        backend.requireApi().deleteDialogueMemory(runId, sessionId, memoryId)
    }

    suspend fun deleteSession(runId: String, sessionId: String) = request {
        backend.requireApi().deleteDialogueSession(runId, sessionId).also {
            appPreferences.forgetSession(runId, sessionId)
        }
    }

    suspend fun deleteSessions(items: List<SessionRefDto>): DeleteSessionsResponse = request {
        backend.requireApi().deleteSessions(DeleteSessionsRequest(items)).also { response ->
            (response.deleted + response.notFound).forEach { session ->
                appPreferences.forgetSession(session.runId, session.sessionId)
            }
        }
    }

    private suspend fun <T> request(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiRequestException) {
            throw error
        } catch (error: HttpException) {
            throw ApiRequestException(
                errorDetail(error.response()?.errorBody()?.string(), error.code()),
                error,
            )
        } catch (error: Throwable) {
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
            return java.net.URLDecoder.decode(encoded.replace("+", "%2B"), Charsets.UTF_8.name())
        }
        return Regex("filename=\\\"?([^;\\\"]+)", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.getOrNull(1).orEmpty()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
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

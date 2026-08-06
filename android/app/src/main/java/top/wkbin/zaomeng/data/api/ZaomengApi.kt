package top.wkbin.zaomeng.data.api

import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface ZaomengApi {
    @GET("api/web/health")
    suspend fun health(): JsonObject

    @GET("api/web/settings/model")
    suspend fun getModelSettings(): ModelSettingsDto

    @PUT("api/web/settings/model")
    suspend fun saveModelSettings(@Body request: SaveModelSettingsRequest): ModelSettingsDto

    @POST("api/web/settings/model/test")
    suspend fun testModelSettings(@Body request: TestModelSettingsRequest): ModelConnectionTestDto

    @POST("api/web/settings/model/profiles/{profileId}/activate")
    suspend fun activateModelProfile(@Path("profileId") profileId: String): ModelSettingsDto

    @DELETE("api/web/settings/model/profiles/{profileId}")
    suspend fun deleteModelProfile(@Path("profileId") profileId: String): ModelSettingsDto

    @GET("api/web/plugins")
    suspend fun listPlugins(): PluginsResponse

    @POST("api/web/plugins/refresh")
    suspend fun refreshPlugins(): PluginsResponse

    @POST("api/web/plugins/packages/inspect")
    suspend fun inspectPluginPackage(
        @Body request: InspectPluginPackageRequest,
    ): PluginPackageInspectionDto

    @POST("api/web/plugins/packages/{token}/install")
    suspend fun installPluginPackage(
        @Path("token") token: String,
        @Body request: InstallPluginPackageRequest,
    ): PluginDto

    @POST("api/web/plugins/{pluginId}/enable")
    suspend fun enablePlugin(@Path("pluginId") pluginId: String): PluginDto

    @POST("api/web/plugins/{pluginId}/disable")
    suspend fun disablePlugin(@Path("pluginId") pluginId: String): PluginDto

    @DELETE("api/web/plugins/{pluginId}")
    suspend fun uninstallPlugin(
        @Path("pluginId") pluginId: String,
    ): UninstallPluginResponse

    @GET("api/web/plugins/{pluginId}/logs")
    suspend fun listPluginLogs(
        @Path("pluginId") pluginId: String,
        @Query("limit") limit: Int = 100,
    ): PluginLogsResponse

    @GET("api/web/plugins/{pluginId}/config")
    suspend fun getPluginConfig(
        @Path("pluginId") pluginId: String,
    ): PluginConfigResponse

    @PUT("api/web/plugins/{pluginId}/config")
    suspend fun updatePluginConfig(
        @Path("pluginId") pluginId: String,
        @Body request: UpdatePluginConfigRequest,
    ): PluginConfigResponse

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/plugins/{pluginId}/actions/{actionId}")
    suspend fun invokePluginChatAction(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Path("pluginId") pluginId: String,
        @Path("actionId") actionId: String,
        @Body request: PluginChatActionRequest,
    ): PluginChatActionResponse

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/plugins/{pluginId}/npc-generators/{generatorId}")
    suspend fun invokePluginTemporaryNpcGenerator(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Path("pluginId") pluginId: String,
        @Path("generatorId") generatorId: String,
        @Body request: PluginTemporaryNpcGeneratorRequest,
    ): PluginTemporaryNpcGeneratorResponse

    @PUT("api/web/runs/{runId}/dialogue/sessions/{sessionId}/plugins/{pluginId}/enhancers/{enhancerId}/state")
    suspend fun setGenerationEnhancerState(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Path("pluginId") pluginId: String,
        @Path("enhancerId") enhancerId: String,
        @Body request: SetGenerationEnhancerStateRequest,
    ): DialogueSessionDto

    @Streaming
    @GET("api/web/diagnostics/export")
    suspend fun exportDiagnostics(): Response<ResponseBody>

    @GET("api/web/runs")
    suspend fun listRuns(): RunsResponse

    @GET("api/web/builtin-novels")
    suspend fun listBuiltinNovels(): BuiltinNovelsResponse

    @POST("api/web/builtin-novels/{packageId}/clone")
    suspend fun cloneBuiltinNovel(@Path("packageId") packageId: String): RunManifestDto

    @POST("api/web/runs")
    suspend fun createRun(@Body request: CreateRunRequest): RunManifestDto

    @POST("api/web/runs/estimate")
    suspend fun estimateSampling(@Body request: EstimateSamplingRequest): SamplingPlanDto

    @POST("api/web/runs/import")
    suspend fun importRun(@Body request: ImportRunPackageRequest): RunManifestDto

    @POST("api/web/crossover-spaces")
    suspend fun createCrossoverSpace(@Body request: CreateCrossoverSpaceRequest): RunManifestDto

    @GET("api/web/runs/{runId}")
    suspend fun getRun(@Path("runId") runId: String): RunManifestDto

    @DELETE("api/web/runs/{runId}")
    suspend fun deleteRun(@Path("runId") runId: String): DeleteRunResponse

    @GET("api/web/runs/{runId}/world-memory")
    suspend fun getWorldMemory(@Path("runId") runId: String): WorldMemoryDto

    @POST("api/web/runs/{runId}/world-memory/facts")
    suspend fun createWorldFact(@Path("runId") runId: String, @Body request: SaveWorldFactRequest): WorldFactDto

    @PUT("api/web/runs/{runId}/world-memory/facts/{factId}")
    suspend fun updateWorldFact(@Path("runId") runId: String, @Path("factId") factId: String, @Body request: SaveWorldFactRequest): WorldFactDto

    @DELETE("api/web/runs/{runId}/world-memory/facts/{factId}")
    suspend fun deleteWorldFact(@Path("runId") runId: String, @Path("factId") factId: String): DeleteStatusDto

    @POST("api/web/runs/{runId}/refresh")
    suspend fun refreshRun(@Path("runId") runId: String): RunManifestDto

    @POST("api/web/runs/{runId}/stop")
    suspend fun stopRun(@Path("runId") runId: String): RunManifestDto

    @POST("api/web/runs/{runId}/redistill")
    suspend fun redistillRun(
        @Path("runId") runId: String,
        @Body request: RestartRunRequest,
    ): RunManifestDto

    @POST("api/web/runs/{runId}/resume-distill")
    suspend fun resumeDistillRun(@Path("runId") runId: String): RunManifestDto

    @POST("api/web/runs/{runId}/redistill/recommend")
    suspend fun suggestRedistillSegments(
        @Path("runId") runId: String,
        @Body request: SuggestRedistillSegmentsRequest,
    ): RedistillSuggestionsDto

    @Streaming
    @GET("api/web/runs/{runId}/export")
    suspend fun exportRun(
        @Path("runId") runId: String,
        @Query("include_dialogue") includeDialogue: Boolean = true,
    ): Response<ResponseBody>

    @GET("api/web/runs/{runId}/chapters")
    suspend fun listChapters(@Path("runId") runId: String): ChaptersResponse

    @GET("api/web/runs/{runId}/search")
    suspend fun searchRunContent(
        @Path("runId") runId: String,
        @Query("query") query: String,
        @Query("limit") limit: Int = 30,
    ): SearchResultsResponse

    @POST("api/web/runs/{runId}/ask")
    suspend fun askBookQuestion(
        @Path("runId") runId: String,
        @Body request: AskBookQuestionRequest,
    ): AskBookResponseDto

    @POST("api/web/runs/{runId}/chapters")
    suspend fun createChapter(@Path("runId") runId: String, @Body request: SaveChapterRequest): ChapterDto

    @PUT("api/web/runs/{runId}/chapters/{chapterId}")
    suspend fun updateChapter(
        @Path("runId") runId: String,
        @Path("chapterId") chapterId: String,
        @Body request: SaveChapterRequest,
    ): ChapterDto

    @PATCH("api/web/runs/{runId}/chapters/{chapterId}/order")
    suspend fun reorderChapter(
        @Path("runId") runId: String,
        @Path("chapterId") chapterId: String,
        @Body request: ReorderChapterRequest,
    ): ChaptersResponse

    @POST("api/web/runs/{runId}/chapters/archive-session")
    suspend fun archiveSessionAsChapter(
        @Path("runId") runId: String,
        @Body request: ArchiveDialogueChapterRequest,
    ): ChapterDto

    @POST("api/web/runs/{runId}/chapters/convert-session")
    suspend fun convertSessionAsNovel(
        @Path("runId") runId: String,
        @Body request: ArchiveDialogueChapterRequest,
    ): ChapterDto

    @DELETE("api/web/runs/{runId}/chapters/{chapterId}")
    suspend fun deleteChapter(
        @Path("runId") runId: String,
        @Path("chapterId") chapterId: String,
    ): DeleteStatusDto

    @POST("api/web/runs/{runId}/chapters/{chapterId}/continue")
    suspend fun continueChapter(
        @Path("runId") runId: String,
        @Path("chapterId") chapterId: String,
    ): DialogueSessionDto

    @POST("api/web/runs/{runId}/chapters/{chapterId}/sync-session")
    suspend fun syncChapterSession(
        @Path("runId") runId: String,
        @Path("chapterId") chapterId: String,
    ): ChapterDto

    @Streaming
    @GET("api/web/runs/{runId}/chapters/export")
    suspend fun exportChapters(
        @Path("runId") runId: String,
        @Query("format") format: String,
    ): Response<ResponseBody>

    @GET("api/web/runs/{runId}/personas/{character}")
    suspend fun getPersona(
        @Path("runId") runId: String,
        @Path("character") character: String,
    ): PersonaReviewDto

    @Multipart
    @POST("api/web/runs/{runId}/personas/{character}/avatar")
    suspend fun uploadPersonaAvatar(
        @Path("runId") runId: String,
        @Path("character") character: String,
        @Part file: MultipartBody.Part,
    ): PersonaAvatarDto

    @Streaming
    @GET("api/web/runs/{runId}/personas/{character}/avatar")
    suspend fun getPersonaAvatar(
        @Path("runId") runId: String,
        @Path("character") character: String,
    ): Response<ResponseBody>

    @PUT("api/web/runs/{runId}/personas/{character}")
    suspend fun savePersona(
        @Path("runId") runId: String,
        @Path("character") character: String,
        @Body fields: JsonObject,
    ): PersonaReviewDto

    @GET("api/web/runs/{runId}/personas/{character}/quality-report")
    suspend fun getPersonaQuality(
        @Path("runId") runId: String,
        @Path("character") character: String,
    ): PersonaQualityReportDto

    @POST("api/web/runs/{runId}/personas/{character}/suggest-field")
    suspend fun suggestPersonaField(
        @Path("runId") runId: String,
        @Path("character") character: String,
        @Body request: SuggestPersonaFieldRequest,
    ): SuggestPersonaFieldResponse

    @GET("api/web/runs/{runId}/relations")
    suspend fun getRelations(@Path("runId") runId: String): RelationDetailsDto

    @PATCH("api/web/runs/{runId}/relations/{pairKey}")
    suspend fun updateRelation(
        @Path("runId") runId: String,
        @Path("pairKey") pairKey: String,
        @Body request: UpdateRelationDetailRequest,
    ): RelationDetailsDto

    @GET("api/web/scene-cards")
    suspend fun listSceneCards(): ReusableCardsResponse

    @GET("api/web/scene-cards/{cardId}")
    suspend fun getSceneCard(@Path("cardId") cardId: String): ReusableCardDto

    @POST("api/web/scene-cards")
    suspend fun createSceneCard(@Body fields: JsonObject): ReusableCardDto

    @PUT("api/web/scene-cards/{cardId}")
    suspend fun updateSceneCard(
        @Path("cardId") cardId: String,
        @Body fields: JsonObject,
    ): ReusableCardDto

    @DELETE("api/web/scene-cards/{cardId}")
    suspend fun deleteSceneCard(@Path("cardId") cardId: String): DeleteStatusDto

    @POST("api/web/scene-cards/generate")
    suspend fun generateSceneCard(): ReusableCardDto

    @POST("api/web/scene-cards/recommend")
    suspend fun recommendSceneCards(@Body request: RecommendSceneCardsRequest): JsonObject

    @GET("api/web/self-cards")
    suspend fun listSelfCards(): ReusableCardsResponse

    @GET("api/web/self-cards/{cardId}")
    suspend fun getSelfCard(@Path("cardId") cardId: String): ReusableCardDto

    @POST("api/web/self-cards")
    suspend fun createSelfCard(@Body fields: JsonObject): ReusableCardDto

    @PUT("api/web/self-cards/{cardId}")
    suspend fun updateSelfCard(
        @Path("cardId") cardId: String,
        @Body fields: JsonObject,
    ): ReusableCardDto

    @DELETE("api/web/self-cards/{cardId}")
    suspend fun deleteSelfCard(@Path("cardId") cardId: String): DeleteStatusDto

    @POST("api/web/self-cards/generate")
    suspend fun generateSelfCard(): ReusableCardDto

    @GET("api/web/opening-presets")
    suspend fun listOpeningPresets(): ReusableCardsResponse

    @GET("api/web/opening-presets/{cardId}")
    suspend fun getOpeningPreset(@Path("cardId") cardId: String): ReusableCardDto

    @POST("api/web/opening-presets")
    suspend fun createOpeningPreset(@Body fields: JsonObject): ReusableCardDto

    @PUT("api/web/opening-presets/{cardId}")
    suspend fun updateOpeningPreset(
        @Path("cardId") cardId: String,
        @Body fields: JsonObject,
    ): ReusableCardDto

    @DELETE("api/web/opening-presets/{cardId}")
    suspend fun deleteOpeningPreset(@Path("cardId") cardId: String): DeleteStatusDto

    @GET("api/web/sessions")
    suspend fun listRecentSessions(): SessionsResponse

    @HTTP(method = "DELETE", path = "api/web/sessions", hasBody = true)
    suspend fun deleteSessions(@Body request: DeleteSessionsRequest): DeleteSessionsResponse

    @GET("api/web/runs/{runId}/dialogue/sessions")
    suspend fun listRunSessions(@Path("runId") runId: String): SessionsResponse

    @POST("api/web/runs/{runId}/dialogue/sessions")
    suspend fun createDialogueSession(
        @Path("runId") runId: String,
        @Body request: CreateDialogueSessionRequest,
    ): DialogueSessionDto

    @GET("api/web/runs/{runId}/dialogue/sessions/{sessionId}")
    suspend fun getDialogueSession(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
    ): DialogueSessionDto

    @GET("api/web/runs/{runId}/dialogue/sessions/{sessionId}/search")
    suspend fun searchDialogueSession(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Query("q") query: String,
        @Query("limit") limit: Int = 50,
    ): ChatSearchResponse

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/recover")
    suspend fun recoverDialogueSession(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Query("force") force: Boolean = false,
    ): DialogueSessionDto

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/reply")
    suspend fun replyDialogue(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: DialogueReplyRequest,
    ): DialogueSessionDto

    @Streaming
    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/reply/stream")
    suspend fun streamDialogueReply(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Header("Idempotency-Key") operationId: String,
        @Body request: DialogueReplyRequest,
    ): Response<ResponseBody>

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/suggest")
    suspend fun suggestDialogue(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: DialogueSuggestionRequest,
    ): DialogueSuggestionResponse

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/correct-latest")
    suspend fun correctLatestDialogue(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
    ): DialogueSessionDto

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/deep-review")
    suspend fun deepReviewLatestDialogue(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
    ): DialogueSessionDto

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/director-options")
    suspend fun directDialogue(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: DialogueDirectorRequest,
    ): JsonObject

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/branch-turn")
    suspend fun branchDialogueTurn(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: BranchDialogueTurnRequest,
    ): DialogueSessionDto

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/branch")
    suspend fun branchDialogueScene(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: BranchDialogueSceneRequest,
    ): DialogueSessionDto

    @PATCH("api/web/runs/{runId}/dialogue/sessions/{sessionId}/branch-meta")
    suspend fun updateDialogueBranchMeta(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: UpdateDialogueBranchMetaRequest,
    ): DialogueSessionDto

    @PUT("api/web/runs/{runId}/dialogue/sessions/{sessionId}/relation-lock")
    suspend fun updateDialogueRelationLock(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: UpdateDialogueRelationLockRequest,
    ): DialogueSessionDto

    @PUT("api/web/runs/{runId}/dialogue/sessions/{sessionId}/scene-card")
    suspend fun switchDialogueScene(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: SwitchDialogueSceneRequest,
    ): DialogueSessionDto

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/scene-card/recommend")
    suspend fun recommendDialogueScene(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
    ): JsonObject

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/memories")
    suspend fun createDialogueMemory(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: UpsertDialogueMemoryRequest,
    ): DialogueSessionDto

    @PUT("api/web/runs/{runId}/dialogue/sessions/{sessionId}/memories/{memoryId}")
    suspend fun updateDialogueMemory(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Path("memoryId") memoryId: String,
        @Body request: UpsertDialogueMemoryRequest,
    ): DialogueSessionDto

    @DELETE("api/web/runs/{runId}/dialogue/sessions/{sessionId}/memories/{memoryId}")
    suspend fun deleteDialogueMemory(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Path("memoryId") memoryId: String,
    ): DialogueSessionDto

    @DELETE("api/web/runs/{runId}/dialogue/sessions/{sessionId}")
    suspend fun deleteDialogueSession(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
    ): DeleteStatusDto
}

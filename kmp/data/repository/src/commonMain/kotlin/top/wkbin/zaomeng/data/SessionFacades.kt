package top.wkbin.zaomeng.data

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import okio.Path
import top.wkbin.zaomeng.data.api.ExportedRunPackage
import top.wkbin.zaomeng.data.api.AuthorNoteDto
import top.wkbin.zaomeng.data.api.ImportedSwipePageDto
import top.wkbin.zaomeng.data.api.CardGreetingsDto
import top.wkbin.zaomeng.data.api.UpdateCardGreetingRequest
import top.wkbin.zaomeng.data.api.ChatSearchResultDto
import top.wkbin.zaomeng.data.api.DeleteSessionsResponse
import top.wkbin.zaomeng.data.api.DeleteStatusDto
import top.wkbin.zaomeng.data.api.DialogueMemoryDto
import top.wkbin.zaomeng.data.api.DialogueOperationStatusResponse
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.DialogueStreamEvent
import top.wkbin.zaomeng.data.api.MemoryQualityReportDto
import top.wkbin.zaomeng.data.api.MessagesResponse
import top.wkbin.zaomeng.data.api.SessionRefDto
import top.wkbin.zaomeng.data.api.SessionProjectionDto
import top.wkbin.zaomeng.data.api.SessionsResponse

interface SessionRepository {
    suspend fun listSessions(runId: String?): List<DialogueSessionDto>

    suspend fun listSessionsPage(
        runId: String? = null,
        offset: Int = 0,
        limit: Int = 50,
        query: String = "",
        sort: String = "recent",
    ): SessionsResponse

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
        cardGreetingIndex: Int? = null,
    ): DialogueSessionDto

    suspend fun getCardGreetings(runId: String): CardGreetingsDto
    suspend fun updateCardGreeting(runId: String, request: UpdateCardGreetingRequest): CardGreetingsDto
    suspend fun importSillyTavernChat(runId: String, filename: String, bytes: ByteArray): DialogueSessionDto
    suspend fun exportSillyTavernChat(session: DialogueSessionDto, cacheDirectory: Path): ExportedRunPackage
    suspend fun listImportedSwipes(runId: String, sessionId: String, messageIndex: String, offset: Int): ImportedSwipePageDto

    suspend fun getSession(
        runId: String,
        sessionId: String,
        includeTranscript: Boolean,
    ): DialogueSessionDto

    suspend fun listSessionMessages(
        runId: String,
        sessionId: String,
        offset: Int,
        limit: Int,
        order: String,
        expectedRevision: Long? = null,
    ): MessagesResponse

    suspend fun getSessionProjection(runId: String, sessionId: String): SessionProjectionDto
    suspend fun rebuildSessionProjection(runId: String, sessionId: String): SessionProjectionDto

    suspend fun getSessionOperation(
        runId: String,
        sessionId: String,
        operationId: String,
    ): DialogueOperationStatusResponse

    suspend fun updateSessionTitle(runId: String, sessionId: String, title: String): DialogueSessionDto
    suspend fun updateAuthorNote(runId: String, sessionId: String, note: AuthorNoteDto): AuthorNoteDto

    suspend fun recoverSession(
        runId: String,
        sessionId: String,
        force: Boolean,
    ): DialogueSessionDto

    suspend fun deleteSession(runId: String, sessionId: String): DeleteStatusDto
    suspend fun deleteSessions(items: List<SessionRefDto>): DeleteSessionsResponse
}

interface DialogueRepository {
    suspend fun searchSession(
        runId: String,
        sessionId: String,
        query: String,
        limit: Int = 50,
    ): List<ChatSearchResultDto>

    fun streamReply(
        runId: String,
        sessionId: String,
        message: String,
        messageKind: String,
        operationId: String,
        pacing: String = "normal",
        speakerOverride: String = "",
        suppressTranscriptMessage: Boolean = messageKind == "plot",
        includeInnerThoughts: Boolean = false,
        includeModelReasoning: Boolean = false,
        includeTranscript: Boolean = false,
        includePromptPreview: Boolean = false,
    ): Flow<DialogueStreamEvent>

    suspend fun suggestReply(
        runId: String,
        sessionId: String,
        seedText: String = "",
        direction: String = "",
    ): String

    suspend fun correctLatestReply(runId: String, sessionId: String): DialogueSessionDto
    suspend fun deepReviewLatestReply(runId: String, sessionId: String): DialogueSessionDto

    suspend fun dialogueDirectorOptions(
        runId: String,
        sessionId: String,
        goal: String,
        action: String = "advance",
    ): JsonObject

    suspend fun getSceneTension(runId: String, sessionId: String): top.wkbin.zaomeng.data.api.SceneTensionDto
    suspend fun getPresetEvents(runId: String, sessionId: String, category: String? = null): List<top.wkbin.zaomeng.data.api.PlotEventPresetDto>

    suspend fun branchDialogueTurn(runId: String, sessionId: String, turnId: String): DialogueSessionDto
    suspend fun branchImportedSwipe(runId: String, sessionId: String, messageIndex: String, swipeIndex: Int): DialogueSessionDto
    suspend fun branchDialogueScene(runId: String, sessionId: String, sceneIndex: Int): DialogueSessionDto

    suspend fun updateDialogueBranchMeta(
        runId: String,
        sessionId: String,
        label: String? = null,
        isMainline: Boolean? = null,
        lockedEventIds: List<String>? = null,
    ): DialogueSessionDto

    suspend fun setDialogueRelationLock(
        runId: String,
        sessionId: String,
        pairKey: String,
        locked: Boolean,
    ): DialogueSessionDto

    suspend fun switchDialogueScene(
        runId: String,
        sessionId: String,
        sceneCardId: String,
        transitionMessage: String,
        autoContinue: Boolean,
    ): DialogueSessionDto

    suspend fun recommendDialogueScene(runId: String, sessionId: String): JsonObject
    suspend fun saveDialogueMemory(runId: String, sessionId: String, memory: DialogueMemoryDto): DialogueSessionDto
    suspend fun deleteDialogueMemory(runId: String, sessionId: String, memoryId: String): DialogueSessionDto
    suspend fun getDialogueMemoryQuality(runId: String, sessionId: String): MemoryQualityReportDto

    suspend fun updateAutomaticMemoryStatus(
        runId: String,
        sessionId: String,
        memoryId: String,
        status: String,
    ): MemoryQualityReportDto

    suspend fun mergeDuplicateDialogueMemories(runId: String, sessionId: String): MemoryQualityReportDto
}

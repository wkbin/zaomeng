package top.wkbin.zaomeng.data

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import top.wkbin.zaomeng.data.api.ChatSearchResultDto
import top.wkbin.zaomeng.data.api.DeleteSessionsResponse
import top.wkbin.zaomeng.data.api.DeleteStatusDto
import top.wkbin.zaomeng.data.api.DialogueMemoryDto
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.DialogueStreamEvent
import top.wkbin.zaomeng.data.api.MemoryQualityReportDto
import top.wkbin.zaomeng.data.api.MessagesResponse
import top.wkbin.zaomeng.data.api.SessionRefDto
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
    ): DialogueSessionDto

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
    ): MessagesResponse

    suspend fun updateSessionTitle(runId: String, sessionId: String, title: String): DialogueSessionDto

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

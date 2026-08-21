package top.wkbin.zaomeng.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.client.platform.ClientLog
import top.wkbin.zaomeng.data.api.ChatSearchResultDto
import top.wkbin.zaomeng.data.api.CreateDialogueSessionRequest
import top.wkbin.zaomeng.data.api.DeleteSessionsRequest
import top.wkbin.zaomeng.data.api.DeleteSessionsResponse
import top.wkbin.zaomeng.data.api.DeleteStatusDto
import top.wkbin.zaomeng.data.api.DialogueMemoryDto
import top.wkbin.zaomeng.data.api.DialogueReplyRequest
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.DialogueStreamEvent
import top.wkbin.zaomeng.data.api.KtorDialogueClient
import top.wkbin.zaomeng.data.api.KtorSessionClient
import top.wkbin.zaomeng.data.api.MemoryQualityReportDto
import top.wkbin.zaomeng.data.api.MessagesResponse
import top.wkbin.zaomeng.data.api.PlotEventPresetDto
import top.wkbin.zaomeng.data.api.SceneTensionDto
import top.wkbin.zaomeng.data.api.SessionRefDto
import top.wkbin.zaomeng.data.api.SessionsResponse
import top.wkbin.zaomeng.data.api.TranscriptItemDto
import top.wkbin.zaomeng.data.api.toDialogueSessionDto
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.domain.chat.ChatSessionGateway
import top.wkbin.zaomeng.domain.sessions.CreateDialogueSessionCommand
import top.wkbin.zaomeng.domain.sessions.CreateDialogueSessionGateway
import top.wkbin.zaomeng.domain.sessions.DeleteDialogueSessionGateway
import top.wkbin.zaomeng.platform.platformIoDispatcher

class SessionRepositoryImpl(
    private val ktorSessions: KtorSessionClient,
    private val ktorDialogue: KtorDialogueClient,
    private val appPreferences: AppPreferencesRepository,
) : SessionRepository {
    override suspend fun listSessions(runId: String?): List<DialogueSessionDto> = repositoryRequest {
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

    override suspend fun listSessionsPage(
        runId: String?,
        offset: Int,
        limit: Int,
        query: String,
        sort: String,
    ): SessionsResponse = repositoryRequest {
        if (runId.isNullOrBlank()) {
            ktorSessions.listRecent(offset = offset, limit = limit, query = query, sort = sort)
        } else {
            ktorSessions.listForRun(runId, offset = offset, limit = limit, query = query, sort = sort)
        }
    }

    override suspend fun createSession(
        runId: String,
        mode: String,
        participants: List<String>,
        controlledCharacter: String,
        selfName: String,
        selfIdentity: String,
        selfStyle: String,
        sceneCardId: String,
        sceneProfile: JsonObject,
        selfCardId: String,
        selfCardProfile: JsonObject,
    ): DialogueSessionDto = repositoryRequest {
        val inlineSelfProfile = buildJsonObject {
            if (selfName.isNotBlank()) put("display_name", kotlinx.serialization.json.JsonPrimitive(selfName))
            if (selfIdentity.isNotBlank()) put("scene_identity", kotlinx.serialization.json.JsonPrimitive(selfIdentity))
            if (selfStyle.isNotBlank()) put("interaction_style", kotlinx.serialization.json.JsonPrimitive(selfStyle))
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
    ): DialogueSessionDto = repositoryRequest {
        ktorSessions.get(runId, sessionId, includeTranscript = includeTranscript)
    }

    override suspend fun listSessionMessages(
        runId: String,
        sessionId: String,
        offset: Int,
        limit: Int,
        order: String,
    ): MessagesResponse = repositoryRequest {
        ktorSessions.listMessages(runId, sessionId, offset, limit, order)
    }

    override suspend fun updateSessionTitle(
        runId: String,
        sessionId: String,
        title: String,
    ): DialogueSessionDto = repositoryRequest {
        ktorSessions.updateTitle(runId, sessionId, title)
    }

    override suspend fun recoverSession(
        runId: String,
        sessionId: String,
        force: Boolean,
    ): DialogueSessionDto = repositoryRequest {
        ktorDialogue.recoverSession(runId, sessionId, force)
    }

    override suspend fun deleteSession(runId: String, sessionId: String): DeleteStatusDto = repositoryRequest {
        ktorSessions.delete(runId, sessionId).also {
            appPreferences.forgetSession(runId, sessionId)
        }
    }

    override suspend fun deleteSessions(items: List<SessionRefDto>): DeleteSessionsResponse = repositoryRequest {
        ktorSessions.deleteBatch(DeleteSessionsRequest(items)).also { response ->
            (response.deleted + response.notFound).forEach { session ->
                appPreferences.forgetSession(session.runId, session.sessionId)
            }
        }
    }
}

class DialogueRepositoryImpl(
    private val ktorDialogue: KtorDialogueClient,
) : DialogueRepository {
    override suspend fun searchSession(
        runId: String,
        sessionId: String,
        query: String,
        limit: Int,
    ): List<ChatSearchResultDto> = repositoryRequest {
        ktorDialogue.searchSession(
            runId = runId,
            sessionId = sessionId,
            query = query.trim(),
            limit = limit.coerceIn(1, 100),
        )
    }

    override fun streamReply(
        runId: String,
        sessionId: String,
        message: String,
        messageKind: String,
        operationId: String,
        pacing: String,
        speakerOverride: String,
        suppressTranscriptMessage: Boolean,
        includeInnerThoughts: Boolean,
        includeModelReasoning: Boolean,
        includeTranscript: Boolean,
    ): Flow<DialogueStreamEvent> = flow {
        try {
            val source = ktorDialogue.streamReply(
                runId = runId,
                sessionId = sessionId,
                payload = DialogueReplyRequest(
                    message = message,
                    messageKind = messageKind,
                    pacing = pacing,
                    speakerOverride = speakerOverride,
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
        val payload = runCatching { repositoryJson.parseToJsonElement(data).jsonObject }.getOrElse {
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
                val decodedSession = runCatching { repositoryJson.decodeFromJsonElement<DialogueSessionDto>(session) }
                    .getOrElse { error ->
                        ClientLog.e("DialogueRepository", "Failed to decode stream complete session. Session JSON: $session", error)
                        throw error
                    }
                DialogueStreamEvent.Complete(
                    session = decodedSession,
                    replayed = payload["replayed"]?.jsonPrimitive?.booleanOrNull ?: false,
                    appendedTranscript = payload["appended_transcript"]
                        ?.let { element ->
                            runCatching {
                                repositoryJson.decodeFromJsonElement<List<TranscriptItemDto>>(element)
                            }.getOrElse { error ->
                                ClientLog.e("DialogueRepository", "Failed to decode appended transcript", error)
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

    override suspend fun suggestReply(
        runId: String,
        sessionId: String,
        seedText: String,
        direction: String,
    ): String = repositoryRequest {
        ktorDialogue.suggestReply(runId, sessionId, seedText, direction)
    }

    override suspend fun correctLatestReply(runId: String, sessionId: String): DialogueSessionDto = repositoryRequest {
        ktorDialogue.correctLatest(runId, sessionId)
    }

    override suspend fun deepReviewLatestReply(runId: String, sessionId: String): DialogueSessionDto = repositoryRequest {
        ktorDialogue.deepReview(runId, sessionId)
    }

    override suspend fun dialogueDirectorOptions(
        runId: String,
        sessionId: String,
        goal: String,
        action: String,
    ): JsonObject = repositoryRequest {
        ktorDialogue.directorOptions(runId, sessionId, goal, action)
    }

    override suspend fun getSceneTension(runId: String, sessionId: String): SceneTensionDto = repositoryRequest {
        ktorDialogue.getSceneTension(runId, sessionId)
    }

    override suspend fun getPresetEvents(
        runId: String,
        sessionId: String,
        category: String?,
    ): List<PlotEventPresetDto> = repositoryRequest {
        ktorDialogue.getPresetEvents(runId, sessionId, category)
    }

    override suspend fun branchDialogueTurn(
        runId: String,
        sessionId: String,
        turnId: String,
    ): DialogueSessionDto = repositoryRequest {
        ktorDialogue.branchFromTurn(runId, sessionId, turnId)
    }

    override suspend fun branchDialogueScene(
        runId: String,
        sessionId: String,
        sceneIndex: Int,
    ): DialogueSessionDto = repositoryRequest {
        ktorDialogue.branchFromScene(runId, sessionId, sceneIndex)
    }

    override suspend fun updateDialogueBranchMeta(
        runId: String,
        sessionId: String,
        label: String?,
        isMainline: Boolean?,
        lockedEventIds: List<String>?,
    ): DialogueSessionDto = repositoryRequest {
        ktorDialogue.updateBranchMeta(runId, sessionId, label, isMainline, lockedEventIds)
    }

    override suspend fun setDialogueRelationLock(
        runId: String,
        sessionId: String,
        pairKey: String,
        locked: Boolean,
    ): DialogueSessionDto = repositoryRequest {
        ktorDialogue.setRelationLock(runId, sessionId, pairKey, locked)
    }

    override suspend fun switchDialogueScene(
        runId: String,
        sessionId: String,
        sceneCardId: String,
        transitionMessage: String,
        autoContinue: Boolean,
    ): DialogueSessionDto = repositoryRequest {
        ktorDialogue.switchScene(runId, sessionId, sceneCardId, transitionMessage, autoContinue)
    }

    override suspend fun recommendDialogueScene(runId: String, sessionId: String): JsonObject = repositoryRequest {
        ktorDialogue.recommendScene(runId, sessionId)
    }

    override suspend fun saveDialogueMemory(
        runId: String,
        sessionId: String,
        memory: DialogueMemoryDto,
    ): DialogueSessionDto = repositoryRequest {
        if (memory.memoryId.isBlank()) {
            ktorDialogue.saveMemory(runId, sessionId, "", memory.text, memory.category, memory.pinned, memory.enabled)
        } else {
            ktorDialogue.saveMemory(runId, sessionId, memory.memoryId, memory.text, memory.category, memory.pinned, memory.enabled)
        }
    }

    override suspend fun deleteDialogueMemory(
        runId: String,
        sessionId: String,
        memoryId: String,
    ): DialogueSessionDto = repositoryRequest {
        ktorDialogue.deleteMemory(runId, sessionId, memoryId)
    }

    override suspend fun getDialogueMemoryQuality(
        runId: String,
        sessionId: String,
    ): MemoryQualityReportDto = repositoryRequest {
        ktorDialogue.getMemoryQuality(runId, sessionId)
    }

    override suspend fun updateAutomaticMemoryStatus(
        runId: String,
        sessionId: String,
        memoryId: String,
        status: String,
    ): MemoryQualityReportDto = repositoryRequest {
        ktorDialogue.updateAutomaticMemoryStatus(runId, sessionId, memoryId, status)
    }

    override suspend fun mergeDuplicateDialogueMemories(
        runId: String,
        sessionId: String,
    ): MemoryQualityReportDto = repositoryRequest {
        ktorDialogue.mergeDuplicateMemories(runId, sessionId)
    }
}

class ChatSessionGatewayImpl(
    private val sessions: SessionRepository,
    private val persona: PersonaRepository,
    private val dialogue: DialogueRepository,
) : ChatSessionGateway {
    override suspend fun listSessions(runId: String?): List<DialogueSessionDto> = sessions.listSessions(runId)

    override suspend fun getSession(
        runId: String,
        sessionId: String,
        includeTranscript: Boolean,
    ): DialogueSessionDto = sessions.getSession(runId, sessionId, includeTranscript)

    override suspend fun listSessionMessages(
        runId: String,
        sessionId: String,
        offset: Int,
        limit: Int,
        order: String,
    ): MessagesResponse = sessions.listSessionMessages(runId, sessionId, offset, limit, order)

    override suspend fun recoverSession(
        runId: String,
        sessionId: String,
        force: Boolean,
    ): DialogueSessionDto = sessions.recoverSession(runId, sessionId, force)

    override suspend fun getPersonaAvatar(
        runId: String,
        character: String,
        version: String,
    ): ByteArray? = persona.getPersonaAvatar(runId, character, version)

    override suspend fun getDialogueMemoryQuality(
        runId: String,
        sessionId: String,
    ): MemoryQualityReportDto = dialogue.getDialogueMemoryQuality(runId, sessionId)
}

class CreateDialogueSessionGatewayImpl(
    private val sessions: SessionRepository,
) : CreateDialogueSessionGateway {
    override suspend fun listSessions(runId: String?): List<DialogueSessionDto> = sessions.listSessions(runId)

    override suspend fun createSession(command: CreateDialogueSessionCommand): DialogueSessionDto = sessions.createSession(
        runId = command.runId,
        mode = command.mode,
        participants = command.participants,
        controlledCharacter = command.controlledCharacter,
        selfName = command.selfName,
        selfIdentity = command.selfIdentity,
        selfStyle = command.selfStyle,
        sceneCardId = command.sceneCardId,
        sceneProfile = command.sceneProfile,
        selfCardId = command.selfCardId,
        selfCardProfile = command.selfCardProfile,
    )
}

class DeleteDialogueSessionGatewayImpl(
    private val sessions: SessionRepository,
) : DeleteDialogueSessionGateway {
    override suspend fun listSessions(runId: String?): List<DialogueSessionDto> = sessions.listSessions(runId)

    override suspend fun deleteSession(runId: String, sessionId: String): DeleteStatusDto =
        sessions.deleteSession(runId, sessionId)
}

package top.wkbin.zaomeng.domain.chat

import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.MemoryQualityReportDto
import top.wkbin.zaomeng.data.api.MessagesResponse

/** Narrow data boundary for opening a chat. */
interface ChatSessionGateway {
    suspend fun listSessions(runId: String? = null): List<DialogueSessionDto>

    suspend fun getSession(
        runId: String,
        sessionId: String,
        includeTranscript: Boolean = true,
    ): DialogueSessionDto

    suspend fun listSessionMessages(
        runId: String,
        sessionId: String,
        offset: Int = 0,
        limit: Int = 100,
        order: String = "asc",
    ): MessagesResponse

    suspend fun recoverSession(
        runId: String,
        sessionId: String,
        force: Boolean = false,
    ): DialogueSessionDto

    suspend fun getPersonaAvatar(runId: String, character: String, version: String): ByteArray?

    suspend fun getDialogueMemoryQuality(runId: String, sessionId: String): MemoryQualityReportDto
}

data class LoadedChatSession(
    val session: DialogueSessionDto,
    val runSessions: List<DialogueSessionDto>,
    val avatarBytes: Map<String, ByteArray>,
    val memoryQuality: MemoryQualityReportDto,
)

/**
 * Opens a chat as one business operation: recover an unfinished session, hydrate the
 * bounded transcript tail, and load optional side data without failing the main chat.
 */
class LoadChatSessionUseCase(
    private val gateway: ChatSessionGateway,
) {
    suspend operator fun invoke(
        runId: String,
        sessionId: String,
        transcriptPageSize: Int = DEFAULT_TRANSCRIPT_PAGE_SIZE,
    ): LoadedChatSession {
        val runSessions = runCatching { gateway.listSessions(runId) }.getOrDefault(emptyList())
        val loadedSession = gateway.getSession(runId, sessionId, includeTranscript = false)
        val session = when {
            loadedSession.status != "ready" -> gateway.recoverSession(runId, sessionId, force = true)
            loadedSession.transcript.isEmpty() && loadedSession.transcriptCount > 0 -> {
                val tail = gateway.listSessionMessages(
                    runId = runId,
                    sessionId = sessionId,
                    offset = 0,
                    limit = transcriptPageSize.coerceAtLeast(1),
                    order = "desc",
                )
                loadedSession.copy(
                    transcript = tail.items.asReversed(),
                    transcriptCount = tail.total,
                )
            }
            else -> loadedSession
        }
        val avatars = session.characterAvatars.mapNotNull { (character, version) ->
            runCatching { gateway.getPersonaAvatar(runId, character, version) }
                .getOrNull()
                ?.let { character to it }
        }.toMap()
        val memoryQuality = runCatching { gateway.getDialogueMemoryQuality(runId, sessionId) }
            .getOrDefault(MemoryQualityReportDto())
        return LoadedChatSession(
            session = session,
            runSessions = runSessions,
            avatarBytes = avatars,
            memoryQuality = memoryQuality,
        )
    }

    private companion object {
        const val DEFAULT_TRANSCRIPT_PAGE_SIZE = 100
    }
}

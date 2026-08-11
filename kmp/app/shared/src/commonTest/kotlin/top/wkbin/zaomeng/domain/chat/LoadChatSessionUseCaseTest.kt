package top.wkbin.zaomeng.domain.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.MemoryQualityReportDto
import top.wkbin.zaomeng.data.api.MessagesResponse
import top.wkbin.zaomeng.data.api.TranscriptItemDto

class LoadChatSessionUseCaseTest {
    @Test
    fun hydratesTranscriptTailAndKeepsOptionalFailuresNonFatal() = runTest {
        val gateway = FakeChatSessionGateway(
            session = DialogueSessionDto(
                runId = "run-1",
                sessionId = "session-1",
                transcriptCount = 2,
                characterAvatars = mapOf("林冲" to "v1"),
            ),
            messages = MessagesResponse(
                items = listOf(
                    TranscriptItemDto(message = "new"),
                    TranscriptItemDto(message = "old"),
                ),
                total = 2,
            ),
            failOptionalData = true,
        )

        val loaded = LoadChatSessionUseCase(gateway)("run-1", "session-1")

        assertEquals(listOf("old", "new"), loaded.session.transcript.map { it.message })
        assertEquals(2, loaded.session.transcriptCount)
        assertTrue(loaded.runSessions.isEmpty())
        assertTrue(loaded.avatarBytes.isEmpty())
        assertEquals(MemoryQualityReportDto(), loaded.memoryQuality)
        assertEquals("desc", gateway.requestedMessageOrder)
    }

    @Test
    fun recoversSessionThatIsNotReady() = runTest {
        val recovered = DialogueSessionDto(
            runId = "run-1",
            sessionId = "session-1",
            status = "ready",
        )
        val gateway = FakeChatSessionGateway(
            session = recovered.copy(status = "pending"),
            recoveredSession = recovered,
        )

        val loaded = LoadChatSessionUseCase(gateway)("run-1", "session-1")

        assertEquals(recovered, loaded.session)
        assertTrue(gateway.recoveryRequested)
    }
}

private class FakeChatSessionGateway(
    private val session: DialogueSessionDto,
    private val recoveredSession: DialogueSessionDto = session,
    private val messages: MessagesResponse = MessagesResponse(),
    private val failOptionalData: Boolean = false,
) : ChatSessionGateway {
    var recoveryRequested = false
    var requestedMessageOrder = ""

    override suspend fun listSessions(runId: String?): List<DialogueSessionDto> {
        if (failOptionalData) error("optional list unavailable")
        return listOf(session)
    }

    override suspend fun getSession(
        runId: String,
        sessionId: String,
        includeTranscript: Boolean,
    ): DialogueSessionDto = session

    override suspend fun listSessionMessages(
        runId: String,
        sessionId: String,
        offset: Int,
        limit: Int,
        order: String,
    ): MessagesResponse {
        requestedMessageOrder = order
        return messages
    }

    override suspend fun recoverSession(
        runId: String,
        sessionId: String,
        force: Boolean,
    ): DialogueSessionDto {
        recoveryRequested = force
        return recoveredSession
    }

    override suspend fun getPersonaAvatar(
        runId: String,
        character: String,
        version: String,
    ): ByteArray? {
        if (failOptionalData) error("optional avatar unavailable")
        return byteArrayOf(1)
    }

    override suspend fun getDialogueMemoryQuality(
        runId: String,
        sessionId: String,
    ): MemoryQualityReportDto {
        if (failOptionalData) error("optional memory unavailable")
        return MemoryQualityReportDto(activeCount = 1)
    }
}

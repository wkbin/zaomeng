package top.wkbin.zaomeng.domain.sessions

import kotlinx.coroutines.test.runTest
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.DeleteStatusDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CreateDialogueSessionUseCaseTest {
    @Test
    fun `normalizes command before creating session`() = runTest {
        val gateway = FakeCreateDialogueSessionGateway()
        val created = CreateDialogueSessionUseCase(gateway)(
            CreateDialogueSessionCommand(
                runId = " run-1 ",
                mode = " observe ",
                participants = listOf(" 林冲 ", "林冲", " 鲁智深 "),
            ),
        )

        assertEquals("created", created.sessionId)
        assertEquals("run-1", gateway.createdCommand?.runId)
        assertEquals(listOf("林冲", "鲁智深"), gateway.createdCommand?.participants)
    }

    @Test
    fun `recovers newly created matching session after uncertain failure`() = runTest {
        val recovered = DialogueSessionDto(
            sessionId = "recovered",
            runId = "run-1",
            mode = "act",
            participants = listOf("林冲"),
            controlledCharacter = "林冲",
        )
        val gateway = FakeCreateDialogueSessionGateway(
            sessionsByCall = listOf(emptyList(), listOf(recovered)),
            createFailure = IllegalStateException("connection closed"),
        )

        val result = CreateDialogueSessionUseCase(gateway)(
            CreateDialogueSessionCommand(
                runId = "run-1",
                mode = "act",
                participants = listOf("林冲"),
                controlledCharacter = "林冲",
            ),
        )

        assertEquals("recovered", result.sessionId)
    }

    @Test
    fun `preserves original failure when no matching session exists`() = runTest {
        val gateway = FakeCreateDialogueSessionGateway(
            sessionsByCall = listOf(emptyList(), emptyList()),
            createFailure = IllegalArgumentException("invalid scene"),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            CreateDialogueSessionUseCase(gateway)(
                CreateDialogueSessionCommand("run-1", "observe", listOf("林冲")),
            )
        }

        assertEquals("invalid scene", error.message)
    }

    @Test
    fun `uncertain delete succeeds when session is already absent`() = runTest {
        val gateway = FakeDeleteDialogueSessionGateway(
            remainingSessions = emptyList(),
            failure = IllegalStateException("connection closed"),
        )

        DeleteDialogueSessionUseCase(gateway)("run-1", "session-1")

        assertEquals(1, gateway.deleteCalls)
    }

    @Test
    fun `uncertain delete preserves failure when session still exists`() = runTest {
        val gateway = FakeDeleteDialogueSessionGateway(
            remainingSessions = listOf(DialogueSessionDto(sessionId = "session-1", runId = "run-1")),
            failure = IllegalStateException("delete rejected"),
        )

        val error = assertFailsWith<IllegalStateException> {
            DeleteDialogueSessionUseCase(gateway)("run-1", "session-1")
        }

        assertEquals("delete rejected", error.message)
    }
}

private class FakeCreateDialogueSessionGateway(
    private val sessionsByCall: List<List<DialogueSessionDto>> = listOf(emptyList()),
    private val createFailure: Throwable? = null,
) : CreateDialogueSessionGateway {
    private var listCall = 0
    var createdCommand: CreateDialogueSessionCommand? = null

    override suspend fun listSessions(runId: String?): List<DialogueSessionDto> =
        sessionsByCall.getOrElse(listCall++) { sessionsByCall.lastOrNull().orEmpty() }

    override suspend fun createSession(command: CreateDialogueSessionCommand): DialogueSessionDto {
        createdCommand = command
        createFailure?.let { throw it }
        return DialogueSessionDto(sessionId = "created", runId = command.runId)
    }
}

private class FakeDeleteDialogueSessionGateway(
    private val remainingSessions: List<DialogueSessionDto>,
    private val failure: Throwable,
) : DeleteDialogueSessionGateway {
    var deleteCalls = 0

    override suspend fun listSessions(runId: String?): List<DialogueSessionDto> = remainingSessions

    override suspend fun deleteSession(runId: String, sessionId: String): DeleteStatusDto {
        deleteCalls += 1
        throw failure
    }
}

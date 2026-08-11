package top.wkbin.zaomeng.domain.sessions

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import top.wkbin.zaomeng.data.api.DeleteStatusDto
import top.wkbin.zaomeng.data.api.DialogueSessionDto

data class CreateDialogueSessionCommand(
    val runId: String,
    val mode: String,
    val participants: List<String>,
    val controlledCharacter: String = "",
    val selfName: String = "",
    val selfIdentity: String = "",
    val selfStyle: String = "",
    val sceneCardId: String = "",
    val sceneProfile: JsonObject = JsonObject(emptyMap()),
    val selfCardId: String = "",
    val selfCardProfile: JsonObject = JsonObject(emptyMap()),
)

interface CreateDialogueSessionGateway {
    suspend fun listSessions(runId: String?): List<DialogueSessionDto>
    suspend fun createSession(command: CreateDialogueSessionCommand): DialogueSessionDto
}

/**
 * Creates a session idempotently from the UI perspective. If the request outcome is uncertain,
 * it looks for a newly-created matching session before surfacing the original error.
 */
class CreateDialogueSessionUseCase(
    private val gateway: CreateDialogueSessionGateway,
) {
    suspend operator fun invoke(command: CreateDialogueSessionCommand): DialogueSessionDto {
        val normalized = command.normalized()
        val knownKeys = optional { gateway.listSessions(normalized.runId).mapTo(mutableSetOf(), ::sessionKey) }
            ?: emptySet()
        return try {
            gateway.createSession(normalized)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val recovered = optional { gateway.listSessions(normalized.runId) }
                ?.firstOrNull { session -> sessionKey(session) !in knownKeys && session.matches(normalized) }
            recovered ?: throw failure
        }
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private fun CreateDialogueSessionCommand.normalized() = copy(
        runId = runId.trim(),
        mode = mode.trim(),
        participants = participants.map(String::trim).filter(String::isNotEmpty).distinct(),
        controlledCharacter = controlledCharacter.trim(),
        selfName = selfName.trim(),
        selfIdentity = selfIdentity.trim(),
        selfStyle = selfStyle.trim(),
        sceneCardId = sceneCardId.trim(),
        selfCardId = selfCardId.trim(),
    )

    private fun DialogueSessionDto.matches(command: CreateDialogueSessionCommand): Boolean =
        runId == command.runId &&
            mode == command.mode &&
            participants.toSet() == command.participants.toSet() &&
            (command.mode != "act" || controlledCharacter == command.controlledCharacter)

    private fun sessionKey(session: DialogueSessionDto): String = "${session.runId}::${session.sessionId}"
}

interface DeleteDialogueSessionGateway {
    suspend fun listSessions(runId: String?): List<DialogueSessionDto>
    suspend fun deleteSession(runId: String, sessionId: String): DeleteStatusDto
}

/** Treats an uncertain delete as successful when the server confirms the session is already gone. */
class DeleteDialogueSessionUseCase(
    private val gateway: DeleteDialogueSessionGateway,
) {
    suspend operator fun invoke(runId: String, sessionId: String) {
        try {
            gateway.deleteSession(runId, sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val stillExists = try {
                gateway.listSessions(runId).any { it.runId == runId && it.sessionId == sessionId }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (stillExists != false) throw failure
        }
    }
}

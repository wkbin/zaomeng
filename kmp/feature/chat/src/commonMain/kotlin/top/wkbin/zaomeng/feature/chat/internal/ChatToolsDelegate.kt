package top.wkbin.zaomeng.feature.chat

import kotlinx.coroutines.CancellationException
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.api.DialogueSessionDto

/** Recovery-aware session and branch mutations used by Chat tools. */
internal class ChatToolsDelegate(private val sessions: SessionRepository) {
    suspend fun sessionMutation(
        runId: String,
        sessionId: String,
        previousSession: DialogueSessionDto?,
        operation: suspend () -> DialogueSessionDto,
    ): DialogueSessionDto = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        val recovered = try {
            sessions.getSession(runId, sessionId, includeTranscript = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        recovered?.takeIf { it != previousSession } ?: throw error
    }

    suspend fun branchMutation(
        runId: String,
        sessionId: String,
        originKind: String,
        originValue: String = "",
        operation: suspend () -> DialogueSessionDto,
    ): DialogueSessionDto {
        val knownSessionIds = try {
            sessions.listSessions(runId).mapTo(mutableSetOf()) { it.sessionId }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        return try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (knownSessionIds == null) throw error
            val recovered = try {
                sessions.listSessions(runId)
                    .asSequence()
                    .filter { it.sessionId !in knownSessionIds }
                    .filter { it.branchOrigin.stringValue("session_id") == sessionId }
                    .filter { it.branchOrigin.stringValue("kind") == originKind }
                    .filter {
                        when (originKind) {
                            "event_timeline", "consistency_correction" ->
                                originValue.isBlank() || it.branchOrigin.stringValue("turn_id") == originValue
                            "scene_timeline" -> it.branchOrigin.stringValue("scene_index") == originValue
                            else -> true
                        }
                    }
                    .singleOrNull()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            recovered ?: throw error
        }
    }
}

package top.wkbin.zaomeng.ktor.routes
import top.wkbin.zaomeng.ktor.http.respondError

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import top.wkbin.zaomeng.data.api.DialogueReplyRequest
import top.wkbin.zaomeng.ktor.services.DialogueOperationException
import top.wkbin.zaomeng.ktor.services.DialogueService
import top.wkbin.zaomeng.ktor.services.SessionRevisionConflictException
import top.wkbin.zaomeng.ktor.services.LlmClient
import top.wkbin.zaomeng.ktor.services.ModelApiKeyService
import top.wkbin.zaomeng.ktor.services.PromptLoader
import top.wkbin.zaomeng.ktor.services.StorageService
import top.wkbin.zaomeng.ktor.services.leanSession
import top.wkbin.zaomeng.ktor.services.withTranscriptCount

/**
 * Dialogue API routes.
 *
 * Phase 3.4: Basic dialogue reply endpoint (non-streaming).
 */
fun Route.dialogueRoutes(dialogueService: DialogueService, storageService: StorageService) {

    /**
     * POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/reply
     *
     * Reply to a dialogue turn (non-streaming).
     */
    post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/reply") {
        val runId = call.parameters["run_id"]
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "Missing run_id")
        val sessionId = call.parameters["session_id"]
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "Missing session_id")

        try {
            val request = call.receive<DialogueReplyRequest>()

            val outcome = dialogueService.replyDialogueTurnOutcome(
                runId = runId,
                sessionId = sessionId,
                message = request.message,
                messageKind = request.messageKind,
                pacing = request.pacing,
                speakerOverride = request.speakerOverride,
                suppressTranscriptMessage = request.suppressTranscriptMessage,
                includeInnerThoughts = request.includeInnerThoughts,
                operationId = request.operationId,
            )
            val session = if (request.includeTranscript) {
                storageService.getDialogueSession(runId, sessionId)
            } else {
                storageService.withCharacterAvatars(outcome.sessionManifest, runId)
            }
            call.respond(
                HttpStatusCode.OK,
                if (request.includeTranscript) withTranscriptCount(session) else leanSession(session),
            )
        } catch (e: DialogueOperationException) {
            call.respondError(HttpStatusCode.Conflict, e.message ?: "Operation conflict")
        } catch (e: SessionRevisionConflictException) {
            call.respondError(HttpStatusCode.Conflict, e.message ?: "Session revision conflict")
        } catch (e: NoSuchElementException) {
            call.respondError(HttpStatusCode.NotFound, e.message ?: "Not found")
        } catch (e: IllegalStateException) {
            call.respondError(HttpStatusCode.NotFound, e.message ?: "Not found")
        } catch (e: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, e.message ?: "Invalid request")
        } catch (e: Exception) {
            call.application.environment.log.error("Error replying to dialogue turn", e)
            call.respondError(HttpStatusCode.InternalServerError, "Internal server error: ${e.message}")
        }
    }

}

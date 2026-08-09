package top.wkbin.zaomeng.ktor.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import top.wkbin.zaomeng.data.api.DialogueReplyRequest
import top.wkbin.zaomeng.ktor.services.DialogueService
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
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing run_id"))
        val sessionId = call.parameters["session_id"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session_id"))

        try {
            val request = call.receive<DialogueReplyRequest>()

            dialogueService.replyDialogueTurn(
                runId = runId,
                sessionId = sessionId,
                message = request.message,
                messageKind = request.messageKind,
                suppressTranscriptMessage = request.suppressTranscriptMessage,
                includeInnerThoughts = request.includeInnerThoughts,
                operationId = request.operationId,
            )
            val session = storageService.getDialogueSession(runId, sessionId)
            call.respond(
                HttpStatusCode.OK,
                if (request.includeTranscript) withTranscriptCount(session) else leanSession(session),
            )
        } catch (e: NoSuchElementException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: Exception) {
            call.application.environment.log.error("Error replying to dialogue turn", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Internal server error: ${e.message}")
            )
        }
    }

}

package top.wkbin.zaomeng.ktor.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import top.wkbin.zaomeng.ktor.models.*
import top.wkbin.zaomeng.ktor.services.*
import top.wkbin.zaomeng.data.api.DeleteSessionsRequest
import top.wkbin.zaomeng.data.api.DeleteSessionsResponse
import top.wkbin.zaomeng.data.api.DeleteStatusDto
import top.wkbin.zaomeng.data.api.CreateDialogueSessionRequest
import top.wkbin.zaomeng.data.api.UpdateDialogueSessionTitleRequest

/**
 * 会话管理路由
 *
 * Phase 4: 写入 API 和状态管理
 */
fun Route.sessionManagementRoutes(sessionService: SessionManagementService) {

    get("/api/web/sessions") {
        call.respond(mapOf("items" to sessionService.listRecentSessions()))
    }

    delete("/api/web/sessions") {
        val request = call.receive<DeleteSessionsRequest>()
        val deleted = request.items.filter { sessionService.deleteDialogueSession(it.runId, it.sessionId) }
        val notFound = request.items - deleted.toSet()
        call.respond(DeleteSessionsResponse(
            status = "ok",
            deletedCount = deleted.size,
            deleted = deleted,
            notFoundCount = notFound.size,
            notFound = notFound,
        ))
    }

    // 创建对话会话
    post("/api/web/runs/{run_id}/dialogue/sessions") {
        val runId = call.parameters["run_id"] ?: return@post call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Missing run_id")
        )

        try {
            val request = call.receive<CreateDialogueSessionRequest>()
            val result = sessionService.openDialogueSession(
                runId = runId,
                mode = request.mode,
                participants = request.participants,
                controlledCharacter = request.controlledCharacter,
                sceneCardId = request.sceneCardId,
                sceneProfile = request.sceneProfile,
                selfCardId = request.selfCardId,
                selfProfile = request.selfProfile,
            )
            call.respond(HttpStatusCode.Created, result)
        } catch (e: NoSuchElementException) {
            call.respond(HttpStatusCode.NotFound, mapOf("detail" to (e.message ?: "Run not found")))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create session"))
        }
    }

    // 获取对话会话
    get("/api/web/runs/{run_id}/dialogue/sessions/{session_id}") {
        val runId = call.parameters["run_id"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Missing run_id")
        )
        val sessionId = call.parameters["session_id"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Missing session_id")
        )

        try {
            val result = sessionService.getDialogueSession(runId, sessionId)
            call.respond(HttpStatusCode.OK, result)
        } catch (e: NoSuchElementException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to get session"))
        }
    }

    get("/api/web/runs/{run_id}/dialogue/sessions") {
        val runId = call.parameters["run_id"].orEmpty()
        if (runId.isBlank()) return@get call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Missing run_id"))
        if (!sessionService.runExists(runId)) return@get call.respond(HttpStatusCode.NotFound, mapOf("detail" to "Run not found"))
        call.respond(mapOf("items" to sessionService.listDialogueSessions(runId)))
    }

    delete("/api/web/runs/{run_id}/dialogue/sessions/{session_id}") {
        val runId = call.parameters["run_id"].orEmpty()
        val sessionId = call.parameters["session_id"].orEmpty()
        if (runId.isBlank() || sessionId.isBlank()) {
            return@delete call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Missing session identifier"))
        }
        if (!sessionService.deleteDialogueSession(runId, sessionId)) {
            return@delete call.respond(HttpStatusCode.NotFound, mapOf("detail" to "Session not found"))
        }
        call.respond(DeleteStatusDto(status = "deleted"))
    }

    // 更新会话标题
    patch("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/title") {
        val runId = call.parameters["run_id"] ?: return@patch call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Missing run_id")
        )
        val sessionId = call.parameters["session_id"] ?: return@patch call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Missing session_id")
        )

        try {
            val request = call.receive<UpdateDialogueSessionTitleRequest>()
            val result = sessionService.updateDialogueSessionTitle(runId, sessionId, request.title)
            call.respond(HttpStatusCode.OK, result)
        } catch (e: NoSuchElementException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Session not found")))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to update title"))
        }
    }

    // 准备对话轮次（写入用户输入）
    post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/prepare") {
        val runId = call.parameters["run_id"] ?: return@post call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Missing run_id")
        )
        val sessionId = call.parameters["session_id"] ?: return@post call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Missing session_id")
        )

        try {
            val request = call.receive<PrepareDialogueTurnRequest>()
            val message = request.message
            val messageKind = request.messageKind
            val operationId = request.operationId

            val result = sessionService.prepareDialogueTurn(
                runId = runId,
                sessionId = sessionId,
                message = message,
                messageKind = messageKind,
                operationId = operationId
            )
            call.respond(HttpStatusCode.OK, result)
        } catch (e: NoSuchElementException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Session not found")))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to prepare turn"))
        }
    }
}

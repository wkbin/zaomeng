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
import top.wkbin.zaomeng.data.api.SessionsResponse
import top.wkbin.zaomeng.data.api.CreateDialogueSessionRequest
import top.wkbin.zaomeng.data.api.UpdateDialogueSessionTitleRequest

/**
 * 会话管理路由
 *
 * Phase 4: 写入 API 和状态管理
 */
fun Route.sessionManagementRoutes(sessionService: SessionManagementService) {

    get("/api/web/sessions") {
        val page = call.sessionPageParams()
        call.respond(sessionPageResponse(
            sessionService.listRecentSessions(page.offset, page.limit, page.query, page.sort),
        ))
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
            val includeTranscript = call.request.queryParameters["include_transcript"]
                ?.toBooleanStrictOrNull()
                ?: true
            val response = if (includeTranscript) {
                withTranscriptCount(result)
            } else {
                leanSession(result)
            }
            call.respond(HttpStatusCode.OK, response)
        } catch (e: NoSuchElementException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to get session"))
        }
    }

    // 会话消息分页（历史懒加载）：order=desc 时 offset 表示跳过最新 N 条
    get("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/messages") {
        val runId = call.parameters["run_id"].orEmpty()
        val sessionId = call.parameters["session_id"].orEmpty()
        if (runId.isBlank() || sessionId.isBlank()) {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Missing session identifier"))
        }
        val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
        val order = call.request.queryParameters["order"].orEmpty().trim()
            .takeIf { it in setOf("asc", "desc") } ?: "asc"
        try {
            val session = sessionService.getDialogueSession(runId, sessionId)
            call.respond(pageTranscript(session, offset, limit, order))
        } catch (e: NoSuchElementException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to list messages"))
        }
    }

    get("/api/web/runs/{run_id}/dialogue/sessions") {
        val runId = call.parameters["run_id"].orEmpty()
        if (runId.isBlank()) return@get call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Missing run_id"))
        if (!sessionService.runExists(runId)) return@get call.respond(HttpStatusCode.NotFound, mapOf("detail" to "Run not found"))
        val page = call.sessionPageParams()
        call.respond(sessionPageResponse(
            sessionService.listDialogueSessions(runId, page.offset, page.limit, page.query, page.sort),
        ))
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

/** 会话列表分页参数：offset/limit/q/sort，均有安全默认值。 */
private data class SessionPageParams(
    val offset: Int,
    val limit: Int,
    val query: String,
    val sort: String,
)

private fun io.ktor.server.application.ApplicationCall.sessionPageParams(): SessionPageParams {
    val offset = request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val limit = (request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
    val query = request.queryParameters["q"].orEmpty().trim().take(120)
    val sort = request.queryParameters["sort"].orEmpty().trim().takeIf { it in setOf("recent", "title") }
        ?: "recent"
    return SessionPageParams(offset = offset, limit = limit, query = query, sort = sort)
}

/** 会话列表响应：与旧格式兼容（items），并新增分页字段。 */
private fun sessionPageResponse(page: SessionsPage): Map<String, Any> = mapOf(
    "items" to page.items,
    "total" to page.total,
    "has_more" to page.hasMore,
)

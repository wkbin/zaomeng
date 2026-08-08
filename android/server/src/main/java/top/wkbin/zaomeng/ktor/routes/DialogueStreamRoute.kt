package top.wkbin.zaomeng.ktor.routes

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.DialogueReplyRequest
import top.wkbin.zaomeng.ktor.services.DialogueStreamService
import top.wkbin.zaomeng.ktor.services.StorageService
import top.wkbin.zaomeng.ktor.utils.SseEncoder

/** Streaming dialogue endpoint matching the App contract (status / delta / complete / error SSE 事件). */
fun Route.dialogueStreamRoutes(dialogueStreamService: DialogueStreamService, storageService: StorageService) {
    post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/reply/stream") {
        val runId = call.parameters["run_id"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Missing run_id"))
        val sessionId = call.parameters["session_id"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Missing session_id"))
        val request = runCatching { call.receive<DialogueReplyRequest>() }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Invalid request body"))
        }
        if (request.message.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Message cannot be blank"))
        }
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            // 1. 初始 status 事件
            write(SseEncoder.encodeEvent("status", buildJsonObject {
                put("phase", "generating")
                put("message", "正在生成回复")
            }))
            flush()
            try {
                // 2. 流式增量 delta 事件
                dialogueStreamService.replyDialogueTurnStream(
                    runId = runId,
                    sessionId = sessionId,
                    message = request.message,
                    messageKind = request.messageKind,
                    includeInnerThoughts = request.includeInnerThoughts,
                    operationId = request.operationId,
                    suppressTranscriptMessage = request.suppressTranscriptMessage,
                    includeModelReasoning = request.includeModelReasoning,
                ).collect { event ->
                    // 真流式：逐事件即时 write + flush（打字机节流 delay(20) 已移除，用户确认恢复真流式）
                    Log.d("DialogueStreamRoute", "write@${System.currentTimeMillis()} ${event.text.take(20)}")
                    write(SseEncoder.encodeEvent("delta", buildJsonObject {
                        put("index", event.index)
                        put("speaker", event.speaker)
                        put("role", event.role)
                        put("field", event.field)
                        put("text", event.text)
                    }))
                    flush()
                }
                // 3. 完成事件：携带更新后的会话
                val session = storageService.getDialogueSession(runId, sessionId)
                write(SseEncoder.encodeEvent("complete", buildJsonObject {
                    put("session", session)
                    put("replayed", false)
                }))
            } catch (error: Exception) {
                // application.log（SLF4J）在 logcat 默认不可见，用 android Log 保证可排查
                Log.e("DialogueStreamRoute", "Streaming dialogue reply failed: ${error.message}", error)
                call.application.log.error("Streaming dialogue reply failed", error)
                write(SseEncoder.encodeEvent("error", buildJsonObject {
                    put("message", error.message ?: "Dialogue reply failed")
                    put("retryable", true)
                }))
            }
            flush()
        }
    }
}

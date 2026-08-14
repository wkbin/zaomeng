package top.wkbin.zaomeng.ktor.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.writeString
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.DialogueReplyRequest
import top.wkbin.zaomeng.ktor.services.DialogueStreamService
import top.wkbin.zaomeng.ktor.services.leanSession
import top.wkbin.zaomeng.ktor.services.StorageService
import top.wkbin.zaomeng.ktor.services.transcriptByTurnId
import top.wkbin.zaomeng.ktor.services.transcriptSize
import top.wkbin.zaomeng.ktor.services.withTranscriptCount
import top.wkbin.zaomeng.ktor.utils.SseEncoder
import top.wkbin.zaomeng.platform.PlatformLog

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
        call.respondBytesWriter(ContentType.Text.EventStream) {
            // 1. 初始 status 事件
            writeString(SseEncoder.encodeEvent("status", buildJsonObject {
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
                    speakerOverride = request.speakerOverride,
                    includeInnerThoughts = request.includeInnerThoughts,
                    operationId = request.operationId,
                    suppressTranscriptMessage = request.suppressTranscriptMessage,
                    includeModelReasoning = request.includeModelReasoning,
                ).collect { event ->
                    val eventName = event.kind.ifBlank { "delta" }
                    val payload = if (eventName == "reset") {
                        buildJsonObject { put("message", event.text) }
                    } else {
                        buildJsonObject {
                            put("index", event.index)
                            put("speaker", event.speaker)
                            put("role", event.role)
                            put("field", event.field)
                            put("text", event.text)
                        }
                    }
                    writeString(SseEncoder.encodeEvent(eventName, payload))
                    flush()
                }
                // 3. 完成事件：携带更新后的会话
                val session = storageService.getDialogueSession(runId, sessionId)
                writeString(SseEncoder.encodeEvent("complete", buildJsonObject {
                    put(
                        "session",
                        if (request.includeTranscript) withTranscriptCount(session) else leanSession(session),
                    )
                    put("replayed", false)
                    put("transcript_count", transcriptSize(session))
                    if (!request.includeTranscript) {
                        // 增量模式：只回传本轮新增的 transcript 条目（按 turn_id 提取，幂等重放同样成立）
                        put("appended_transcript", buildJsonArray {
                            transcriptByTurnId(session, request.operationId).forEach(::add)
                        })
                    }
                }))
            } catch (error: Exception) {
                // application.log（SLF4J）在 logcat 默认不可见，用 android Log 保证可排查
                PlatformLog.e("DialogueStreamRoute", "Streaming dialogue reply failed: ${error.message}", error)
                call.application.log.error("Streaming dialogue reply failed", error)
                writeString(SseEncoder.encodeEvent("error", buildJsonObject {
                    put("message", error.message ?: "Dialogue reply failed")
                    put("retryable", true)
                }))
            }
            flush()
        }
    }
}

package top.wkbin.zaomeng.ktor.routes
import top.wkbin.zaomeng.ktor.http.respondError

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.writeString
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.DialogueReplyRequest
import top.wkbin.zaomeng.data.api.DialoguePromptPreviewDto
import top.wkbin.zaomeng.ktor.services.DialogueOperationException
import top.wkbin.zaomeng.ktor.services.DialogueStreamFrame
import top.wkbin.zaomeng.ktor.services.DialogueStreamService
import top.wkbin.zaomeng.ktor.services.ReplyOperationBegin
import top.wkbin.zaomeng.ktor.services.SessionRevisionConflictException
import top.wkbin.zaomeng.ktor.services.leanSession
import top.wkbin.zaomeng.ktor.services.transcriptSize
import top.wkbin.zaomeng.ktor.services.withTranscriptCount
import top.wkbin.zaomeng.ktor.services.withoutWorldBookTimingDelta
import top.wkbin.zaomeng.ktor.utils.SseEncoder
import top.wkbin.zaomeng.platform.PlatformLog

/** Streaming dialogue endpoint matching the App contract (status / delta / complete / error SSE 事件). */
fun Route.dialogueStreamRoutes(dialogueStreamService: DialogueStreamService) {
    get("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/operations/{operation_id}") {
        val runId = call.parameters["run_id"].orEmpty()
        val sessionId = call.parameters["session_id"].orEmpty()
        val operationId = call.parameters["operation_id"].orEmpty()
        if (runId.isBlank() || sessionId.isBlank() || operationId.isBlank()) {
            return@get call.respondError(HttpStatusCode.BadRequest, "Missing operation identifier")
        }
        try {
            call.respond(dialogueStreamService.operationStatus(runId, sessionId, operationId))
        } catch (error: NoSuchElementException) {
            call.respondError(HttpStatusCode.NotFound, error.message ?: "Operation not found")
        } catch (error: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, error.message ?: "Invalid operation_id")
        }
    }

    post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/reply/stream") {
        val runId = call.parameters["run_id"]
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "Missing run_id")
        val sessionId = call.parameters["session_id"]
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "Missing session_id")
        val request = runCatching { call.receive<DialogueReplyRequest>() }.getOrElse {
            return@post call.respondError(HttpStatusCode.BadRequest, "Invalid request body")
        }
        if (request.message.isBlank()) {
            return@post call.respondError(HttpStatusCode.BadRequest, "Message cannot be blank")
        }
        val prepared = try {
            dialogueStreamService.preflight(
                runId = runId,
                sessionId = sessionId,
                message = request.message,
                messageKind = request.messageKind,
                pacing = request.pacing,
                speakerOverride = request.speakerOverride,
                includeInnerThoughts = request.includeInnerThoughts,
                operationId = request.operationId,
                suppressTranscriptMessage = request.suppressTranscriptMessage,
            )
        } catch (error: DialogueOperationException) {
            return@post call.respond(HttpStatusCode.Conflict, buildJsonObject {
                put("code", error.code)
                put("message", error.message ?: "Operation conflict")
                put("retryable", error.retryable)
            })
        } catch (error: SessionRevisionConflictException) {
            return@post call.respond(HttpStatusCode.Conflict, buildJsonObject {
                put("code", "SESSION_REVISION_CONFLICT")
                put("message", error.message ?: "Session revision conflict")
                put("retryable", false)
            })
        } catch (error: NoSuchElementException) {
            return@post call.respondError(HttpStatusCode.NotFound, error.message ?: "Session not found")
        }

        var handedOff = false
        try {
            call.respondBytesWriter(ContentType.Text.EventStream) {
            writeString(SseEncoder.encodeEvent("status", buildJsonObject {
                put("phase", if (prepared is ReplyOperationBegin.Replay) "restoring" else "generating")
                put("message", if (prepared is ReplyOperationBegin.Replay) "正在恢复已提交结果" else "正在生成回复")
            }))
            flush()
            try {
                val frames = dialogueStreamService.replyDialogueTurnFrames(
                    runId = runId,
                    sessionId = sessionId,
                    message = request.message,
                    messageKind = request.messageKind,
                    pacing = request.pacing,
                    speakerOverride = request.speakerOverride,
                    includeInnerThoughts = request.includeInnerThoughts,
                    operationId = request.operationId,
                    suppressTranscriptMessage = request.suppressTranscriptMessage,
                    includeModelReasoning = request.includeModelReasoning,
                    prepared = prepared,
                    includePromptPreview = request.includePromptPreview,
                )
                handedOff = true
                frames.collect { frame ->
                    when (frame) {
                        is DialogueStreamFrame.PromptPreview -> writeString(SseEncoder.encodeEvent(
                            "prompt_preview", Json.encodeToJsonElement(DialoguePromptPreviewDto.serializer(), frame.preview),
                        ))
                        is DialogueStreamFrame.Delta -> {
                            val event = frame.event
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
                        }
                        is DialogueStreamFrame.Completed -> {
                            val outcome = frame.outcome
                            val session = dialogueStreamService.responseSession(outcome, request.includeTranscript)
                            writeString(SseEncoder.encodeEvent("complete", buildJsonObject {
                                put(
                                    "session",
                                    if (request.includeTranscript) withTranscriptCount(session) else leanSession(session),
                                )
                                put("operation_id", outcome.operationId)
                                put("turn_id", outcome.turnId)
                                put("replayed", outcome.replayed)
                                put("base_revision", outcome.baseRevision)
                                put("committed_revision", outcome.committedRevision)
                                put("transcript_count", transcriptSize(session))
                                if (!request.includeTranscript) {
                                    put("appended_transcript", buildJsonArray {
                                        outcome.appendedTranscript.forEach { add(it.withoutWorldBookTimingDelta()) }
                                    })
                                }
                            }))
                        }
                    }
                    flush()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PlatformLog.e("DialogueStreamRoute", "Streaming dialogue reply failed: ${error.message}", error)
                call.application.log.error("Streaming dialogue reply failed", error)
                val code = when (error) {
                    is DialogueOperationException -> error.code
                    is SessionRevisionConflictException -> "SESSION_REVISION_CONFLICT"
                    else -> "OPERATION_FAILED"
                }
                val retryable = when (error) {
                    is DialogueOperationException -> error.retryable
                    is SessionRevisionConflictException -> false
                    else -> true
                }
                writeString(SseEncoder.encodeEvent("error", buildJsonObject {
                    put("code", code)
                    put("operation_id", request.operationId)
                    put("message", error.message ?: "Dialogue reply failed")
                    put("retryable", retryable)
                }))
            }
            flush()
            }
        } catch (error: Throwable) {
            if (!handedOff && prepared is ReplyOperationBegin.Active) {
                dialogueStreamService.abort(prepared, error)
            }
            throw error
        }
    }
}

package top.wkbin.zaomeng.ktor.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.writeString
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.Serializable
import top.wkbin.zaomeng.ktor.services.*
import top.wkbin.zaomeng.ktor.utils.SseEncoder

/**
 * 对话建议路由
 *
 * 处理对话建议和联想请求
 */
fun Route.suggestionsRoutes(suggestionsService: SuggestionsService) {
    authenticate {
        // POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/suggestions
        post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/suggestions") {
            val runId = call.parameters["run_id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing run_id"))
                return@post
            }

            val sessionId = call.parameters["session_id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session_id"))
                return@post
            }

            // 检查是否请求流式响应
            val stream = call.request.queryParameters["stream"]?.toBoolean() ?: true

            val request = try {
                call.receive<SuggestionRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                return@post
            }

            try {
                if (stream) {
                    // 流式响应
                    call.response.header(HttpHeaders.ContentType, "text/event-stream")
                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    call.response.header(HttpHeaders.Connection, "keep-alive")

                    call.respondBytesWriter(ContentType.Text.EventStream) {
                        suggestionsService.generateSuggestionStream(
                            runId = runId,
                            sessionId = sessionId,
                            seedText = request.seedText ?: "",
                            selectedDirection = request.selectedDirection ?: ""
                        )
                            .catch { error ->
                                val errorSse = SseEncoder.encodeEvent(
                                    event = "error",
                                    "error" to error.message,
                                    "type" to "suggestion_error"
                                )
                                writeString(errorSse)
                                flush()
                            }
                            .collect { delta ->
                                val sse = SseEncoder.encodeEvent(
                                    event = "delta",
                                    "text" to delta
                                )
                                writeString(sse)
                                flush()
                            }

                        val doneSse = SseEncoder.encodeEvent(
                            event = "done",
                            "status" to "completed"
                        )
                        writeString(doneSse)
                        flush()
                    }
                } else {
                    // 非流式响应（暂不实现）
                    call.respond(HttpStatusCode.NotImplemented, mapOf(
                        "error" to "Non-streaming suggestions not implemented. Use stream=true."
                    ))
                }
            } catch (e: Exception) {
                call.application.log.error("Error generating suggestions", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Internal server error"))
                )
            }
        }

        // POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/associations
        post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/associations") {
            val runId = call.parameters["run_id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing run_id"))
                return@post
            }

            val sessionId = call.parameters["session_id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session_id"))
                return@post
            }

            val request = try {
                call.receive<AssociationRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                return@post
            }

            try {
                // 生成联想选项
                val options = suggestionsService.generateAssociations(
                    runId = runId,
                    sessionId = sessionId,
                    optionCount = request.optionCount?.coerceIn(2, 4) ?: 3
                )

                call.respond(
                    HttpStatusCode.OK,
                    mapOf("options" to options)
                )
            } catch (e: Exception) {
                call.application.log.error("Error generating associations", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Internal server error"))
                )
            }
        }
    }
}

@Serializable
private data class SuggestionRequest(
    val seedText: String? = null,
    val selectedDirection: String? = null
)

@Serializable
private data class AssociationRequest(
    val optionCount: Int? = null
)

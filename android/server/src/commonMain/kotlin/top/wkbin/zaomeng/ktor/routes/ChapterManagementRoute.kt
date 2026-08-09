package top.wkbin.zaomeng.ktor.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.ArchiveDialogueChapterRequest
import top.wkbin.zaomeng.data.api.AskBookQuestionRequest
import top.wkbin.zaomeng.data.api.ReorderChapterRequest
import top.wkbin.zaomeng.data.api.SaveChapterRequest
import top.wkbin.zaomeng.ktor.services.ChapterManagementService

/**
 * 章节管理路由
 *
 * 对应 Python src/web/api/routes/chapters.py：
 * search / ask / 章节 CRUD / archive-session / convert-session /
 * continue / sync-session / reorder / export
 */
fun Route.chapterManagementRoutes(service: ChapterManagementService) {
    // GET /api/web/runs/{run_id}/search?query=&limit=
    get("/api/web/runs/{run_id}/search") {
        val runId = call.parameters["run_id"].orEmpty()
        val query = call.request.queryParameters["query"]?.trim().orEmpty()
        if (query.isEmpty() || query.length > 100) {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "query 需为 1-100 字"))
        }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 30
        runRoute(call) { buildJsonObject { put("items", service.search(runId, query, limit)) } }
    }

    // POST /api/web/runs/{run_id}/ask
    post("/api/web/runs/{run_id}/ask") {
        val runId = call.parameters["run_id"].orEmpty()
        val request = call.receive<AskBookQuestionRequest>()
        runRoute(call) { service.ask(runId, request.question) }
    }

    // POST /api/web/runs/{run_id}/chapters（创建章节）
    post("/api/web/runs/{run_id}/chapters") {
        val runId = call.parameters["run_id"].orEmpty()
        val request = call.receive<SaveChapterRequest>()
        runRoute(call) {
            service.save(
                runId,
                chapterId = "",
                title = request.title,
                goal = request.goal,
                participants = request.participants,
                content = request.content,
            )
        }
    }

    // PUT /api/web/runs/{run_id}/chapters/{chapter_id}（更新章节）
    put("/api/web/runs/{run_id}/chapters/{chapter_id}") {
        val runId = call.parameters["run_id"].orEmpty()
        val chapterId = call.parameters["chapter_id"].orEmpty()
        val request = call.receive<SaveChapterRequest>()
        runRoute(call) {
            service.save(
                runId,
                chapterId = chapterId,
                title = request.title,
                goal = request.goal,
                participants = request.participants,
                content = request.content,
            )
        }
    }

    // PATCH /api/web/runs/{run_id}/chapters/{chapter_id}/order
    patch("/api/web/runs/{run_id}/chapters/{chapter_id}/order") {
        val runId = call.parameters["run_id"].orEmpty()
        val chapterId = call.parameters["chapter_id"].orEmpty()
        val request = call.receive<ReorderChapterRequest>()
        runRoute(call) { service.reorder(runId, chapterId, request.targetOrder) }
    }

    // POST /api/web/runs/{run_id}/chapters/archive-session
    post("/api/web/runs/{run_id}/chapters/archive-session") {
        val runId = call.parameters["run_id"].orEmpty()
        val request = call.receive<ArchiveDialogueChapterRequest>()
        runRoute(call) { service.archive(runId, request.sessionId, request.title) }
    }

    // POST /api/web/runs/{run_id}/chapters/convert-session
    post("/api/web/runs/{run_id}/chapters/convert-session") {
        val runId = call.parameters["run_id"].orEmpty()
        val request = call.receive<ArchiveDialogueChapterRequest>()
        runRoute(call) { service.convert(runId, request.sessionId, request.title) }
    }

    // DELETE /api/web/runs/{run_id}/chapters/{chapter_id}
    delete("/api/web/runs/{run_id}/chapters/{chapter_id}") {
        val runId = call.parameters["run_id"].orEmpty()
        val chapterId = call.parameters["chapter_id"].orEmpty()
        runRoute(call) { service.delete(runId, chapterId) }
    }

    // POST /api/web/runs/{run_id}/chapters/{chapter_id}/continue
    post("/api/web/runs/{run_id}/chapters/{chapter_id}/continue") {
        val runId = call.parameters["run_id"].orEmpty()
        val chapterId = call.parameters["chapter_id"].orEmpty()
        runRoute(call) { service.continueWriting(runId, chapterId) }
    }

    // POST /api/web/runs/{run_id}/chapters/{chapter_id}/sync-session
    post("/api/web/runs/{run_id}/chapters/{chapter_id}/sync-session") {
        val runId = call.parameters["run_id"].orEmpty()
        val chapterId = call.parameters["chapter_id"].orEmpty()
        runRoute(call) { service.sync(runId, chapterId) }
    }

    // GET /api/web/runs/{run_id}/chapters/export?format=markdown|text
    get("/api/web/runs/{run_id}/chapters/export") {
        val runId = call.parameters["run_id"].orEmpty()
        val format = call.request.queryParameters["format"]?.lowercase() ?: "markdown"
        try {
            val rendered = service.render(runId, format)
            val normalized = if (format == "text") "text" else "markdown"
            val filename = "$runId-manuscript.${if (normalized == "text") "txt" else "md"}"
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString(),
            )
            call.respondBytes(
                rendered.encodeToByteArray(),
                ContentType.Text.Plain.withParameter("charset", "UTF-8"),
            )
        } catch (e: NoSuchElementException) {
            call.respond(HttpStatusCode.NotFound, mapOf("detail" to (e.message ?: "Not found")))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("detail" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            call.application.log.error("Chapter export failed", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("detail" to (e.message ?: "Internal server error")))
        }
    }
}

private suspend fun runRoute(
    call: ApplicationCall,
    block: suspend () -> JsonObject,
) {
    try {
        call.respond(HttpStatusCode.OK, block())
    } catch (e: NoSuchElementException) {
        call.respond(HttpStatusCode.NotFound, mapOf("detail" to (e.message ?: "Not found")))
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, mapOf("detail" to (e.message ?: "Invalid request")))
    } catch (e: Exception) {
        call.application.log.error("Chapter management route failed", e)
        call.respond(HttpStatusCode.InternalServerError, mapOf("detail" to (e.message ?: "Internal server error")))
    }
}

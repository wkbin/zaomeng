package top.wkbin.zaomeng.ktor.routes
import top.wkbin.zaomeng.ktor.http.respondError

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import top.wkbin.zaomeng.data.api.RewriteChapterRequest
import top.wkbin.zaomeng.ktor.services.ChapterService

fun Route.chapterRoutes(service: ChapterService) {
    post("/api/web/runs/{run_id}/chapters/{chapter_id}/rewrite") {
        val runId = call.parameters["run_id"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "Missing run_id")
        val chapterId = call.parameters["chapter_id"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "Missing chapter_id")
        try {
            val request = call.receive<RewriteChapterRequest>()
            call.respond(HttpStatusCode.OK, service.rewrite(runId, chapterId, request.instruction, request.contextSummary))
        } catch (e: NoSuchElementException) {
            call.respondError(HttpStatusCode.NotFound, (e.message ?: "Not found"))
        } catch (e: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, (e.message ?: "Invalid request"))
        } catch (e: Exception) {
            call.application.environment.log.error("Chapter rewrite failed", e)
            call.respondError(HttpStatusCode.BadGateway, (e.message ?: "Rewrite failed"))
        }
    }
}

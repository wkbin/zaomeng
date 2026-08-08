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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.CreateCrossoverSpaceRequest
import top.wkbin.zaomeng.data.api.EstimateSamplingRequest
import top.wkbin.zaomeng.data.api.RestartRunRequest
import top.wkbin.zaomeng.data.api.SuggestRedistillSegmentsRequest
import top.wkbin.zaomeng.ktor.services.RunOperationsService

/**
 * 运行操作路由
 *
 * 对应 Python src/web/api/routes/runs.py 的：
 * builtin-novels / estimate / crossover-spaces / export /
 * redistill / resume-distill / redistill-recommend / refresh
 */
fun Route.runOperationsRoutes(service: RunOperationsService) {
    // GET /api/web/builtin-novels
    get("/api/web/builtin-novels") {
        runOpsCall(call) { buildJsonObject { put("items", service.listBuiltinNovels()) } }
    }

    // POST /api/web/builtin-novels/{package_id}/clone
    post("/api/web/builtin-novels/{package_id}/clone") {
        val packageId = call.parameters["package_id"].orEmpty()
        runOpsCall(call) { service.cloneBuiltinNovel(packageId) }
    }

    // POST /api/web/runs/estimate
    post("/api/web/runs/estimate") {
        val request = call.receive<EstimateSamplingRequest>()
        runOpsCall(call) {
            service.estimate(
                charCount = request.charCount,
                sentenceCount = request.sentenceCount,
                characterCount = request.characterCount,
                maxSentences = request.maxSentences,
                maxChars = request.maxChars,
            )
        }
    }

    // POST /api/web/crossover-spaces
    post("/api/web/crossover-spaces") {
        val request = call.receive<CreateCrossoverSpaceRequest>()
        runOpsCall(call) {
            service.createCrossoverSpace(
                title = request.title,
                worldSetting = request.worldSetting,
                participants = request.participants.map { it.runId to it.character },
            )
        }
    }

    // GET /api/web/runs/{run_id}/export?builtin=&include_dialogue=
    get("/api/web/runs/{run_id}/export") {
        val runId = call.parameters["run_id"].orEmpty()
        val builtin = call.request.queryParameters["builtin"]?.toBoolean() ?: false
        val includeDialogue = call.request.queryParameters["include_dialogue"]?.toBoolean()
        try {
            val (bytes, filename) = service.exportRunPackage(runId, builtin, includeDialogue)
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString(),
            )
            call.respondBytes(bytes, ContentType.Application.Zip)
        } catch (e: NoSuchElementException) {
            call.respond(HttpStatusCode.NotFound, mapOf("detail" to (e.message ?: "Not found")))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("detail" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            call.application.log.error("Run export failed", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("detail" to (e.message ?: "Internal server error")))
        }
    }

    // POST /api/web/runs/{run_id}/redistill
    post("/api/web/runs/{run_id}/redistill") {
        val runId = call.parameters["run_id"].orEmpty()
        val request = call.receive<RestartRunRequest>()
        runOpsCall(call) {
            service.redistill(
                runId,
                characters = request.characters,
                novelName = request.novelName,
                novelContentBase64 = request.novelContentBase64,
                maxSentences = request.maxSentences,
                maxChars = request.maxChars,
            )
        }
    }

    // POST /api/web/runs/{run_id}/resume-distill
    post("/api/web/runs/{run_id}/resume-distill") {
        val runId = call.parameters["run_id"].orEmpty()
        runOpsCall(call) { service.resumeDistill(runId) }
    }

    // POST /api/web/runs/{run_id}/redistill/recommend
    post("/api/web/runs/{run_id}/redistill/recommend") {
        val runId = call.parameters["run_id"].orEmpty()
        val request = call.receive<SuggestRedistillSegmentsRequest>()
        runOpsCall(call) {
            service.suggestRedistillSegments(runId, request.character, request.maxSegments)
        }
    }

    // POST /api/web/runs/{run_id}/refresh
    post("/api/web/runs/{run_id}/refresh") {
        val runId = call.parameters["run_id"].orEmpty()
        runOpsCall(call) { service.refresh(runId) }
    }
}

private suspend fun runOpsCall(call: ApplicationCall, block: suspend () -> JsonObject) {
    try {
        call.respond(HttpStatusCode.OK, block())
    } catch (e: NoSuchElementException) {
        call.respond(HttpStatusCode.NotFound, mapOf("detail" to (e.message ?: "Not found")))
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, mapOf("detail" to (e.message ?: "Invalid request")))
    } catch (e: Exception) {
        call.application.log.error("Run operations route failed", e)
        call.respond(HttpStatusCode.InternalServerError, mapOf("detail" to (e.message ?: "Internal server error")))
    }
}

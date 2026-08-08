package top.wkbin.zaomeng.ktor.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import kotlinx.serialization.json.JsonObject
import top.wkbin.zaomeng.data.api.UpdateRelationDetailRequest
import top.wkbin.zaomeng.ktor.services.RelationsService

/**
 * 人物关系路由
 *
 * 对应 Python src/web/api/routes/runs.py 的 GET/PATCH relations。
 */
fun Route.relationsRoutes(service: RelationsService) {
    // GET /api/web/runs/{run_id}/relations
    get("/api/web/runs/{run_id}/relations") {
        val runId = call.parameters["run_id"].orEmpty()
        relationsCall(call) { service.list(runId) }
    }

    // PATCH /api/web/runs/{run_id}/relations/{pair_key}
    patch("/api/web/runs/{run_id}/relations/{pair_key}") {
        val runId = call.parameters["run_id"].orEmpty()
        val pairKey = call.parameters["pair_key"].orEmpty()
        val request = call.receive<UpdateRelationDetailRequest>()
        relationsCall(call) {
            service.update(
                runId,
                pairKey,
                trust = request.trust,
                affection = request.affection,
                hostility = request.hostility,
                ambiguity = request.ambiguity,
                relationshipType = request.relationshipType,
                relationChange = request.relationChange,
                conflictPoint = request.conflictPoint,
                typicalInteraction = request.typicalInteraction,
            )
        }
    }
}

private suspend fun relationsCall(call: ApplicationCall, block: suspend () -> JsonObject) {
    try {
        call.respond(HttpStatusCode.OK, block())
    } catch (e: NoSuchElementException) {
        call.respond(HttpStatusCode.NotFound, mapOf("detail" to (e.message ?: "Not found")))
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, mapOf("detail" to (e.message ?: "Invalid request")))
    } catch (e: Exception) {
        call.application.log.error("Relations route failed", e)
        call.respond(HttpStatusCode.InternalServerError, mapOf("detail" to (e.message ?: "Internal server error")))
    }
}

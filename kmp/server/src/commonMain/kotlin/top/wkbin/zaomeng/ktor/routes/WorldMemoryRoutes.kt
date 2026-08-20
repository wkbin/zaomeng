package top.wkbin.zaomeng.ktor.routes
import top.wkbin.zaomeng.ktor.http.respondError

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import top.wkbin.zaomeng.data.api.SaveWorldFactRequest
import top.wkbin.zaomeng.ktor.services.WorldMemoryService

fun Route.worldMemoryRoutes(service: WorldMemoryService) {
    get("/api/web/runs/{run_id}/world-memory") {
        worldMemoryCall { runId -> call.respond(service.get(runId)) }
    }
    post("/api/web/runs/{run_id}/world-memory/facts") {
        worldMemoryCall { runId -> call.respond(HttpStatusCode.Created, service.saveFact(runId, "", call.receive<SaveWorldFactRequest>())) }
    }
    put("/api/web/runs/{run_id}/world-memory/facts/{fact_id}") {
        worldMemoryCall { runId ->
            val factId = call.parameters["fact_id"] ?: throw IllegalArgumentException("Missing fact_id")
            call.respond(service.saveFact(runId, factId, call.receive<SaveWorldFactRequest>()))
        }
    }
    delete("/api/web/runs/{run_id}/world-memory/facts/{fact_id}") {
        worldMemoryCall { runId ->
            val factId = call.parameters["fact_id"] ?: throw IllegalArgumentException("Missing fact_id")
            call.respond(service.deleteFact(runId, factId))
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.worldMemoryCall(block: suspend (String) -> Unit) {
    val runId = call.parameters["run_id"] ?: return call.respondError(HttpStatusCode.BadRequest, "Missing run_id")
    try {
        block(runId)
    } catch (error: NoSuchElementException) {
        call.respondError(HttpStatusCode.NotFound, (error.message ?: "Not found"))
    } catch (error: IllegalArgumentException) {
        call.respondError(HttpStatusCode.BadRequest, (error.message ?: "Invalid request"))
    }
}

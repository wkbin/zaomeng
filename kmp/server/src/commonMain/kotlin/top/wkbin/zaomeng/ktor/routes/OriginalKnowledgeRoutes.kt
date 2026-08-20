package top.wkbin.zaomeng.ktor.routes
import top.wkbin.zaomeng.ktor.http.respondError

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.SearchOriginalKnowledgeRequest
import top.wkbin.zaomeng.data.api.UpdateOriginalKnowledgeBoundaryRequest
import top.wkbin.zaomeng.data.api.UpdateOriginalKnowledgePinnedRequest
import top.wkbin.zaomeng.ktor.services.OriginalKnowledgeService
import top.wkbin.zaomeng.ktor.services.StorageService

fun Route.originalKnowledgeRoutes(
    service: OriginalKnowledgeService,
    storage: StorageService,
) {
    get("/api/web/runs/{run_id}/original-knowledge") {
        originalKnowledgeCall(storage) { _, manifest ->
            call.respond(service.ensure(manifest))
        }
    }
    post("/api/web/runs/{run_id}/original-knowledge/rebuild") {
        originalKnowledgeCall(storage) { _, manifest ->
            call.respond(service.rebuild(manifest))
        }
    }
    post("/api/web/runs/{run_id}/original-knowledge/search") {
        originalKnowledgeCall(storage) { _, manifest ->
            val request = call.receive<SearchOriginalKnowledgeRequest>()
            val items = service.search(
                        runManifest = manifest,
                        query = request.query,
                participants = request.participants,
                limit = request.limit,
                pinnedOnly = request.pinnedOnly,
                    )
            call.respond(buildJsonObject {
                put("items", buildJsonArray {
                    items.forEach { item -> add(item.toJson()) }
                })
            })
        }
    }
    put("/api/web/runs/{run_id}/original-knowledge/entries/{entry_id}/boundary") {
        originalKnowledgeCall(storage) { runId, _ ->
            val entryId = call.parameters["entry_id"] ?: throw IllegalArgumentException("Missing entry_id")
            val request = call.receive<UpdateOriginalKnowledgeBoundaryRequest>()
            call.respond(service.updateBoundary(runId, entryId, request.visibility, request.knowers))
        }
    }
    put("/api/web/runs/{run_id}/original-knowledge/entries/{entry_id}/pinned") {
        originalKnowledgeCall(storage) { runId, _ ->
            val entryId = call.parameters["entry_id"] ?: throw IllegalArgumentException("Missing entry_id")
            val request = call.receive<UpdateOriginalKnowledgePinnedRequest>()
            call.respond(service.updatePinned(runId, entryId, request.pinned))
        }
    }
}

private fun Map<String, Any?>.toJson(): JsonObject = buildJsonObject {
    put("source_id", this@toJson["source_id"]?.toString().orEmpty())
    put("title", this@toJson["title"]?.toString().orEmpty())
    put("excerpt", this@toJson["excerpt"]?.toString().orEmpty())
    put("score", (this@toJson["score"] as? Number)?.toDouble() ?: 0.0)
    put("visibility", this@toJson["visibility"]?.toString().orEmpty())
    put("knowers", stringArray(this@toJson["knowers"]))
    put("characters", stringArray(this@toJson["characters"]))
    put("boundary_source", this@toJson["boundary_source"]?.toString().orEmpty())
    put("pinned", this@toJson["pinned"] as? Boolean ?: false)
    put("epistemic_status", this@toJson["epistemic_status"]?.toString().orEmpty())
    put("allowed_characters", stringArray(this@toJson["allowed_characters"]))
    put("denied_characters", stringArray(this@toJson["denied_characters"]))
    val location = this@toJson["location"] as? Map<*, *>
    put("location", buildJsonObject {
        put("start_char", (location?.get("start_char") as? Number)?.toInt() ?: 0)
        put("end_char", (location?.get("end_char") as? Number)?.toInt() ?: 0)
    })
}

private fun stringArray(value: Any?): kotlinx.serialization.json.JsonArray = buildJsonArray {
    (value as? List<*>)?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
        ?.distinct()
        ?.forEach { add(JsonPrimitive(it)) }
}

private suspend fun RoutingContext.originalKnowledgeCall(
    storage: StorageService,
    block: suspend (String, JsonObject) -> Unit,
) {
    val runId = call.parameters["run_id"]
        ?: return call.respondError(HttpStatusCode.BadRequest, "Missing run_id")
    val manifest = storage.readRunManifest(runId)
        ?: return call.respondError(HttpStatusCode.NotFound, "Run not found")
    try {
        block(runId, manifest)
    } catch (error: NoSuchElementException) {
        call.respondError(HttpStatusCode.NotFound, (error.message ?: "Not found"))
    } catch (error: IllegalArgumentException) {
        call.respondError(HttpStatusCode.BadRequest, (error.message ?: "Invalid request"))
    }
}

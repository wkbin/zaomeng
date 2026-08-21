package top.wkbin.zaomeng.ktor.routes
import top.wkbin.zaomeng.ktor.http.respondError

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.*
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.ktor.models.SuggestPersonaFieldRequest
import top.wkbin.zaomeng.ktor.services.PersonaService

fun Route.personaRoutes(service: PersonaService) {
    route("/api/web/runs/{run_id}/personas/{character}") {
        get {
            personaCall { runId, character -> call.respond(service.getReview(runId, character)) }
        }
        put {
            personaCall { runId, character ->
                val payload = call.receive<JsonObject>()
                val fields = payload.mapValues { it.value.jsonPrimitive.content }
                call.respond(service.saveReview(runId, character, fields))
            }
        }
        delete {
            personaCall { runId, character -> call.respond(service.deletePersona(runId, character)) }
        }
        get("/quality-report") {
            personaCall { runId, character -> call.respond(service.getQualityReport(runId, character)) }
        }
        get("/repair-proposal") {
            personaCall { runId, character -> call.respond(service.getRepairProposal(runId, character)) }
        }
        post("/avatar") {
            personaCall { runId, character ->
                var bytes: ByteArray? = null
                call.receiveMultipart().forEachPart { part ->
                    if (part is PartData.FileItem && part.name == "file") bytes = part.provider().toByteArray()
                    part.release()
                }
                call.respond(service.saveAvatar(runId, character, requireNotNull(bytes) { "Avatar file is required." }))
            }
        }
        get("/avatar") {
            personaCall { runId, character ->
                call.respondBytes(service.getAvatarBytes(runId, character), io.ktor.http.ContentType.Image.PNG)
            }
        }
        post("/suggest-field") {
            personaCall { runId, character ->
                val request = call.receive<SuggestPersonaFieldRequest>()
                call.respond(service.suggestField(runId, character, request.field, request.currentFields))
            }
        }
        post("/evolve/proposal") {
            personaCall { runId, character ->
                val request = runCatching { call.receive<top.wkbin.zaomeng.data.api.GenerateEvolutionProposalRequest>() }.getOrNull()
                call.respond(service.generateEvolutionProposal(runId, character, request?.recap))
            }
        }
        post("/evolve/apply") {
            personaCall { runId, character ->
                val request = call.receive<top.wkbin.zaomeng.data.api.ApplyPersonaEvolutionRequest>()
                call.respond(service.applyEvolution(runId, character, request.changes))
            }
        }
    }
}

private suspend fun RoutingContext.personaCall(block: suspend (String, String) -> Unit) {
    val runId = call.parameters["run_id"] ?: return call.respondError(HttpStatusCode.BadRequest, "Missing run_id")
    val character = call.parameters["character"] ?: return call.respondError(HttpStatusCode.BadRequest, "Missing character")
    try {
        block(runId, character)
    } catch (error: NoSuchElementException) {
        call.respondError(HttpStatusCode.NotFound, (error.message ?: "Not found"))
    } catch (error: IllegalArgumentException) {
        call.respondError(HttpStatusCode.BadRequest, (error.message ?: "Invalid request"))
    }
}

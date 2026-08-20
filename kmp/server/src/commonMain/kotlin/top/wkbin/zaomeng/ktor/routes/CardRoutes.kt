package top.wkbin.zaomeng.ktor.routes
import top.wkbin.zaomeng.ktor.http.respondError

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import top.wkbin.zaomeng.ktor.services.CardsService

fun Route.cardRoutes(service: CardsService) {
    post("/api/web/scene-cards/generate") {
        respondCard(call, service::generateSceneCard)
    }
    post("/api/web/self-cards/generate") {
        respondCard(call, service::generateSelfCard)
    }
}

private suspend fun respondCard(call: ApplicationCall, generator: suspend () -> kotlinx.serialization.json.JsonObject) {
    try {
        call.respond(HttpStatusCode.OK, generator())
    } catch (e: IllegalArgumentException) {
        call.respondError(HttpStatusCode.BadRequest, (e.message ?: "Invalid request"))
    } catch (e: Exception) {
        call.application.environment.log.error("Card generation failed", e)
        call.respondError(HttpStatusCode.BadGateway, (e.message ?: "Card generation failed"))
    }
}

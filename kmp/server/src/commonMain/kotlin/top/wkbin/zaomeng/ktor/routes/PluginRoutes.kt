package top.wkbin.zaomeng.ktor.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.request.receive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.Serializable
import top.wkbin.zaomeng.ktor.services.PluginService

fun Route.pluginRoutes(service: PluginService) {
    get("/api/web/plugins") { call.respond(service.list()) }
    post("/api/web/plugins/refresh") { call.respond(service.list()) }
    post("/api/web/plugins/{plugin_id}/enable") { updatePlugin(call, service, true) }
    post("/api/web/plugins/{plugin_id}/disable") { updatePlugin(call, service, false) }
    get("/api/web/plugins/{plugin_id}/config") {
        try { call.respond(mapOf("config" to service.getConfig(call.parameters["plugin_id"].orEmpty()))) }
        catch (e: Exception) { call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Plugin not found"))) }
    }
    put("/api/web/plugins/{plugin_id}/config") {
        try {
            val request = call.receive<PluginConfigRequest>()
            call.respond(mapOf("config" to service.updateConfig(call.parameters["plugin_id"].orEmpty(), request.config)))
        } catch (e: Exception) { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid config"))) }
    }
    get("/api/web/plugins/{plugin_id}/logs") {
        try { call.respond(service.logs(call.parameters["plugin_id"].orEmpty())) }
        catch (e: Exception) { call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Plugin not found"))) }
    }
    delete("/api/web/plugins/{plugin_id}") {
        try { call.respond(service.uninstall(call.parameters["plugin_id"].orEmpty())) }
        catch (e: Exception) { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Unable to uninstall plugin"))) }
    }
}

@Serializable
private data class PluginConfigRequest(val config: JsonObject)

private suspend fun updatePlugin(call: ApplicationCall, service: PluginService, enabled: Boolean) {
    val id = call.parameters["plugin_id"] ?: return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing plugin_id"))
    try {
        call.respond(HttpStatusCode.OK, service.setEnabled(id, enabled))
    } catch (e: NoSuchElementException) {
        call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Plugin not found")))
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid plugin")))
    }
}

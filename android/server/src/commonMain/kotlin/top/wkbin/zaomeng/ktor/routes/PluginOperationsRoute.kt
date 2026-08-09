package top.wkbin.zaomeng.ktor.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonObject
import top.wkbin.zaomeng.data.api.InstallPluginPackageRequest
import top.wkbin.zaomeng.data.api.InspectPluginPackageRequest
import top.wkbin.zaomeng.data.api.PluginChatActionRequest
import top.wkbin.zaomeng.data.api.SetGenerationEnhancerStateRequest
import top.wkbin.zaomeng.data.api.PluginTemporaryNpcGeneratorRequest
import top.wkbin.zaomeng.ktor.services.PluginOperationsService

/**
 * 插件包管理路由
 *
 * 对应 Python src/web/api/routes/plugins.py 的：
 * packages/inspect、packages/{token}/install，以及对话中插件动作端点。
 */
fun Route.pluginOperationsRoutes(service: PluginOperationsService) {
    // POST /api/web/plugins/packages/inspect
    post("/api/web/plugins/packages/inspect") {
        val request = call.receive<InspectPluginPackageRequest>()
        pluginOpsCall(call) { service.inspect(request.filename, request.contentBase64) }
    }

    // POST /api/web/plugins/packages/{token}/install
    post("/api/web/plugins/packages/{token}/install") {
        val token = call.parameters["token"].orEmpty()
        val request = call.receive<InstallPluginPackageRequest>()
        pluginOpsCall(call) { service.install(token, request.confirmPermissions, request.allowUpdate) }
    }

    // POST .../sessions/{session_id}/plugins/{plugin_id}/actions/{action_id}
    post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/plugins/{plugin_id}/actions/{action_id}") {
        val runId = call.parameters["run_id"].orEmpty()
        val sessionId = call.parameters["session_id"].orEmpty()
        val pluginId = call.parameters["plugin_id"].orEmpty()
        val actionId = call.parameters["action_id"].orEmpty()
        val request = call.receive<PluginChatActionRequest>()
        pluginOpsCall(call) {
            service.invokeChatAction(runId, sessionId, pluginId, actionId, request.seedText, request.direction)
        }
    }

    // POST .../sessions/{session_id}/plugins/{plugin_id}/npc-generators/{generator_id}
    post("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/plugins/{plugin_id}/npc-generators/{generator_id}") {
        val runId = call.parameters["run_id"].orEmpty()
        val sessionId = call.parameters["session_id"].orEmpty()
        val pluginId = call.parameters["plugin_id"].orEmpty()
        val generatorId = call.parameters["generator_id"].orEmpty()
        val request = call.receive<PluginTemporaryNpcGeneratorRequest>()
        pluginOpsCall(call) { service.invokeNpcGenerator(runId, sessionId, pluginId, generatorId, request.direction) }
    }

    // PUT .../sessions/{session_id}/plugins/{plugin_id}/enhancers/{enhancer_id}/state
    put("/api/web/runs/{run_id}/dialogue/sessions/{session_id}/plugins/{plugin_id}/enhancers/{enhancer_id}/state") {
        val runId = call.parameters["run_id"].orEmpty()
        val sessionId = call.parameters["session_id"].orEmpty()
        val pluginId = call.parameters["plugin_id"].orEmpty()
        val enhancerId = call.parameters["enhancer_id"].orEmpty()
        val request = call.receive<SetGenerationEnhancerStateRequest>()
        pluginOpsCall(call) { service.setEnhancerState(runId, sessionId, pluginId, enhancerId, request.enabled) }
    }
}

private suspend fun pluginOpsCall(call: ApplicationCall, block: suspend () -> JsonObject) {
    try {
        call.respond(HttpStatusCode.OK, block())
    } catch (e: NoSuchElementException) {
        call.respond(HttpStatusCode.NotFound, mapOf("detail" to (e.message ?: "Not found")))
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, mapOf("detail" to (e.message ?: "Invalid request")))
    } catch (e: Exception) {
        call.application.log.error("Plugin operations route failed", e)
        call.respond(HttpStatusCode.InternalServerError, mapOf("detail" to (e.message ?: "Internal server error")))
    }
}

package top.wkbin.zaomeng.ktor.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import top.wkbin.zaomeng.data.api.CreateRunRequest
import top.wkbin.zaomeng.ktor.services.*

/**
 * 运行管理路由
 *
 * Phase 4: 写入 API 和状态管理
 */
fun Route.runManagementRoutes(runService: RunManagementService, packageService: RunPackageService) {

    post("/api/web/runs/import") {
        try {
            call.respond(HttpStatusCode.Created, packageService.importPackage(call.receive<top.wkbin.zaomeng.data.api.ImportRunPackageRequest>()))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("detail" to (e.message ?: "Invalid run package")))
        }
    }

    // 创建新运行
    post("/api/web/runs") {
        try {
            val request = call.receive<CreateRunRequest>()
            val result = runService.createRun(
                novelName = request.novelName,
                novelContentBase64 = request.novelContentBase64,
                characters = request.characters,
                maxSentences = request.maxSentences,
                maxChars = request.maxChars,
                autoRun = request.autoRun,
                deferRun = request.deferRun
            )
            call.respond(HttpStatusCode.Created, result)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create run"))
        }
    }

    // 停止运行
    post("/api/web/runs/{run_id}/control/stop") {
        stopRun(call, runService)
    }

    // Python/WebUI compatibility alias.
    post("/api/web/runs/{run_id}/stop") {
        stopRun(call, runService)
    }

    // 删除运行
    delete("/api/web/runs/{run_id}") {
        val runId = call.parameters["run_id"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Missing run_id")
        )

        try {
            val result = runService.deleteRun(runId)
            call.respond(HttpStatusCode.OK, result)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Run not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to delete run"))
        }
    }
}

private suspend fun stopRun(call: ApplicationCall, runService: RunManagementService) {
    val runId = call.parameters["run_id"] ?: run {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing run_id"))
        return
    }

    try {
        val result = runService.stopRun(runId)
        call.respond(HttpStatusCode.OK, result)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Run not found"))
    } catch (e: Exception) {
        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to stop run"))
    }
}

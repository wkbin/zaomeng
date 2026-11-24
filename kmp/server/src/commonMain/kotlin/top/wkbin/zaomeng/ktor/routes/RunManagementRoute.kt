package top.wkbin.zaomeng.ktor.routes
import top.wkbin.zaomeng.ktor.http.respondError

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import top.wkbin.zaomeng.data.api.CreateRunRequest
import top.wkbin.zaomeng.data.api.ImportRunPackageRequest
import top.wkbin.zaomeng.data.api.SillyTavernPreviewRequest
import top.wkbin.zaomeng.ktor.services.*
import top.wkbin.zaomeng.platform.platformIoDispatcher

/**
 * 运行管理路由
 *
 * Phase 4: 写入 API 和状态管理
 */
fun Route.runManagementRoutes(runService: RunManagementService, packageService: RunPackageService) {
    worldBookRoutes(packageService.sillyTavern)
    post("/api/web/runs/import/sillytavern/preview") {
        try {
            val request = call.receive<SillyTavernPreviewRequest>()
            call.respond(withContext(platformIoDispatcher) { packageService.previewSillyTavern(request) })
        } catch (e: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, e.message ?: "无效的 SillyTavern 文件。")
        } catch (_: NoSuchElementException) {
            call.respondError(HttpStatusCode.NotFound, "目标书卷不存在。")
        }
    }

    post("/api/web/runs/import") {
        try {
            val request = call.receive<ImportRunPackageRequest>()
            val result = withContext(platformIoDispatcher) { packageService.importPackage(request) }
            call.respond(HttpStatusCode.Created, result)
        } catch (e: WorldBookChangedException) {
            call.respondError(HttpStatusCode.Conflict, e.message.orEmpty())
        } catch (e: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, (e.message ?: "Invalid run package"))
        } catch (_: NoSuchElementException) {
            call.respondError(HttpStatusCode.NotFound, "目标书卷不存在。")
        }
    }

    // 创建新运行
    post("/api/web/runs") {
        try {
            val request = call.receive<CreateRunRequest>()
            val result = withContext(platformIoDispatcher) {
                runService.createRun(
                    novelName = request.novelName,
                    novelContentBase64 = request.novelContentBase64,
                    characters = request.characters,
                    maxSentences = request.maxSentences,
                    maxChars = request.maxChars,
                    autoRun = request.autoRun,
                    deferRun = request.deferRun,
                )
            }
            call.respond(HttpStatusCode.Created, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, (e.message ?: "Invalid request"))
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.InternalServerError, "Failed to create run")
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
        val runId = call.parameters["run_id"] ?: return@delete call.respondError(HttpStatusCode.BadRequest, "Missing run_id")

        try {
            val result = withContext(platformIoDispatcher) { runService.deleteRun(runId) }
            call.respond(HttpStatusCode.OK, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            call.respondError(HttpStatusCode.NotFound, "Run not found")
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.InternalServerError, "Failed to delete run")
        }
    }
}

private suspend fun stopRun(call: ApplicationCall, runService: RunManagementService) {
    val runId = call.parameters["run_id"] ?: run {
        call.respondError(HttpStatusCode.BadRequest, "Missing run_id")
        return
    }

    try {
        val result = withContext(platformIoDispatcher) { runService.stopRun(runId) }
        call.respond(HttpStatusCode.OK, result)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        call.respondError(HttpStatusCode.NotFound, "Run not found")
    } catch (e: Exception) {
        call.respondError(HttpStatusCode.InternalServerError, "Failed to stop run")
    }
}

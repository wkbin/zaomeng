package top.wkbin.zaomeng.ktor.routes
import top.wkbin.zaomeng.ktor.http.respondError

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.ktor.services.StorageService

/**
 * 运行相关的 API 路由
 *
 * 对应 Python FastAPI 的 runs 相关端点
 */
fun Route.runsRoute(storageService: StorageService) {
    route("/api/web/runs") {
        // GET /api/web/runs - 列出所有运行
        get {
            try {
                val manifests = storageService.listRunManifests()
                call.respond(HttpStatusCode.OK, buildJsonObject {
                    put("items", buildJsonArray {
                        manifests.forEach { add(it) }
                    })
                })
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.InternalServerError, (e.message ?: "Failed to list runs"))
            }
        }

        // GET /api/web/runs/{run_id} - 获取单个运行的清单
        get("/{run_id}") {
            val runId = call.parameters["run_id"] ?: run {
                call.respondError(HttpStatusCode.BadRequest, "Missing run_id")
                return@get
            }

            try {
                val manifest = storageService.readRunManifest(runId)
                if (manifest == null) {
                    call.respondError(HttpStatusCode.NotFound, "Run not found")
                } else {
                    // 响应注入实时 avatar_version（对齐 Python core.py _serialize_manifest）
                    call.respond(HttpStatusCode.OK, storageService.withLiveAvatarVersions(manifest, runId))
                }
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.InternalServerError, (e.message ?: "Failed to read run manifest"))
            }
        }

        // GET /api/web/runs/{run_id}/chapters - 列出章节
        get("/{run_id}/chapters") {
            val runId = call.parameters["run_id"] ?: run {
                call.respondError(HttpStatusCode.BadRequest, "Missing run_id")
                return@get
            }

            try {
                if (!storageService.runExists(runId)) {
                    call.respondError(HttpStatusCode.NotFound, "Run not found")
                    return@get
                }

                val chapters = storageService.listChapters(runId)
                call.respond(HttpStatusCode.OK, buildJsonObject {
                    put("items", buildJsonArray { chapters.forEach { add(it) } })
                })
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.InternalServerError, (e.message ?: "Failed to list chapters"))
            }
        }
    }
}

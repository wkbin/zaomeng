package top.wkbin.zaomeng.ktor.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import top.wkbin.zaomeng.ktor.services.DiagnosticsService
import top.wkbin.zaomeng.ktor.services.StorageService

/**
 * 诊断 API 路由
 *
 * 对应 Python FastAPI 的 diagnostics 端点
 */
fun Route.diagnosticsRoute(
    storageService: StorageService,
    diagnosticsService: DiagnosticsService
) {
    route("/api/web/diagnostics") {
        // GET /api/web/diagnostics/export - 导出诊断报告
        get("/export") {
            try {
                val report = diagnosticsService.buildDiagnosticsReport()

                call.response.headers.append(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"zaomeng-diagnostics.json\""
                )

                call.respond(HttpStatusCode.OK, report)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("detail" to (e.message ?: "Failed to build diagnostics report"))
                )
            }
        }
    }
}

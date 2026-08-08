package top.wkbin.zaomeng.ktor.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val backend: String,
)

/**
 * 健康检查路由
 *
 * 提供 /api/web/health 端点，与 Python FastAPI 版本兼容
 */
fun Route.healthRoute() {
    get("/api/web/health") {
        call.respond(
            HttpStatusCode.OK,
            HealthResponse(
                status = "ok",
                version = "0.1.0",
                backend = "ktor",
            )
        )
    }
}

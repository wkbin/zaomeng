package top.wkbin.zaomeng.ktor.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import top.wkbin.zaomeng.ktor.http.respondError

/**
 * Bearer Token 认证配置
 *
 * 对应 Python FastAPI 的 Bearer authentication 中间件
 */
fun Application.configureSecurity(authToken: String) {
    if (authToken.isBlank()) {
        // 无 token 时不启用认证
        return
    }

    install(Authentication) {
        bearer {
            authenticate { credential ->
                if (credential.token == authToken) {
                    UserIdPrincipal("zaomeng-user")
                } else {
                    null
                }
            }
        }
    }

    // 认证拦截器
    intercept(ApplicationCallPipeline.Call) {
        val path = call.request.path()

        // 健康检查端点不需要认证
        if (path == "/api/web/health") {
            return@intercept
        }

        // API 路由需要认证
        val isProtectedPath = path.startsWith("/api/web/") && path != "/api/web/health"

        if (isProtectedPath && authToken.isNotBlank()) {
            val authorization = call.request.header(HttpHeaders.Authorization)
            val token = authorization?.removePrefix("Bearer ")?.trim()

            if (token != authToken) {
                call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
                call.respondError(HttpStatusCode.Unauthorized, "Bearer authentication is required.")
                finish()
            }
        }
    }
}

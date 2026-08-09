package top.wkbin.zaomeng.ktor.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respond
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import top.wkbin.zaomeng.platform.PlatformLog

fun Application.configureObservability() {
    // ktor-server-call-logging 无 Kotlin/Native 变体，用 Monitoring 拦截器实现等价请求日志，
    // 三端行为一致（输出到 PlatformLog 与应用日志）。
    intercept(ApplicationCallPipeline.Monitoring) {
        try {
            proceed()
        } finally {
            val status = call.response.status()?.value?.toString() ?: "-"
            val line = "${call.request.httpMethod.value} ${call.request.uri} -> $status"
            PlatformLog.i(TAG, line)
            call.application.environment.log.info(line)
        }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            PlatformLog.e(TAG, "Unhandled Ktor request failure: ${call.request.httpMethod.value} ${call.request.uri}", cause)
            call.application.environment.log.error(
                "Unhandled Ktor request failure: ${call.request.httpMethod.value} ${call.request.uri}",
                cause,
            )
            if (!call.response.isCommitted) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("detail" to "Internal server error"))
            }
        }
    }

}

private const val TAG = "ZaomengServer"

package top.wkbin.zaomeng.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.bearerAuth
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.createHttpClientEngine

/** Shared Ktor Client configuration（跨平台版，引擎由 server 的 expect/actual 提供）。 */
class KtorHttpClientProvider(
    private val endpointProvider: BackendEndpointProvider,
    engine: HttpClientEngine = createHttpClientEngine(),
) {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    val client = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
        install(Logging) {
            // 注意：不可使用 LogLevel.BODY —— BODY 级日志会让插件整体读取响应体（把流式 SSE 缓冲成一次性）。
            level = LogLevel.HEADERS
            logger = object : Logger {
                override fun log(message: String) {
                    PlatformLog.d("KtorClient", message)
                }
            }
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
            if (headers[HttpHeaders.Authorization] == null) bearerAuth(endpointProvider.currentToken())
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 5 * 60_000
            socketTimeoutMillis = 5 * 60_000
        }
        expectSuccess = false
    }

    fun close() = client.close()

    /** 当前安装 token（供 OkHttp 原生流式请求使用）。 */
    fun bearerToken(): String = endpointProvider.currentToken()
}

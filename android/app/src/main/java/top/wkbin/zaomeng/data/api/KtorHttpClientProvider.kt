package top.wkbin.zaomeng.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.client.request.bearerAuth
import top.wkbin.zaomeng.backend.InstallationTokenStore
import kotlinx.serialization.json.Json

/** Shared Ktor Client configuration for the post-Retrofit API migration. */
class KtorHttpClientProvider(private val tokenStore: InstallationTokenStore) {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(Logging) {
            // 注意：不可使用 LogLevel.BODY —— BODY 级日志会让插件整体读取响应体（把流式 SSE 缓冲成一次性）。
            // 仅记录请求/响应行与 header，避免破坏 reply/stream 的逐事件流式。
            level = LogLevel.HEADERS
            logger = object : Logger {
                override fun log(message: String) {
                    android.util.Log.d("KtorClient", message)
                }
            }
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
            if (headers[HttpHeaders.Authorization] == null) bearerAuth(tokenStore.getOrCreate())
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 5 * 60_000
            socketTimeoutMillis = 5 * 60_000
        }
        expectSuccess = false
    }

    fun close() = client.close()

    /** 当前安装 token（供 OkHttp 原生流式请求使用，与 defaultRequest 的 bearerAuth 同源）。 */
    fun bearerToken(): String = tokenStore.getOrCreate()
}

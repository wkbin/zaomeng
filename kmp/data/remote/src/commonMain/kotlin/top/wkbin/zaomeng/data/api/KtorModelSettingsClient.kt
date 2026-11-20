package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.backend.BackendEndpoint

class KtorModelSettingsClient(
    private val http: KtorHttpClientProvider,
    private val endpointProvider: BackendEndpointProvider,
) {
    suspend fun get(): ModelSettingsDto = request { endpoint ->
        http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/settings/model") {
        }
    }.body()

    suspend fun save(payload: SaveModelSettingsRequest): ModelSettingsDto = request { endpoint ->
        http.client.put("${endpoint.baseUrl.trimEnd('/')}/api/web/settings/model") {
            setBody(payload)
        }
    }.body()

    suspend fun discoverModels(payload: DiscoverModelsRequest): AvailableModelsDto {
        val endpoint = endpointProvider.requireKtorEndpoint()
        val response = http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/settings/model/discover") {
            setBody(payload)
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            val message = runCatching { http.json.decodeFromString<Map<String, String>>(body)["error"] }.getOrNull()
            throw IllegalStateException(message ?: "拉取模型列表失败（HTTP ${response.status.value}）。")
        }
        return response.body()
    }

    suspend fun test(payload: TestModelSettingsRequest): ModelConnectionTestDto {
        val result: ModelConnectionTestDto = request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/settings/model/test") {
                timeout {
                    requestTimeoutMillis = (MAX_MODEL_REQUEST_TIMEOUT_SECONDS + 30) * 1000L
                    socketTimeoutMillis = requestTimeoutMillis
                }
                setBody(payload)
            }
        }.body()
        if (!result.ok) throw IllegalStateException(result.message.ifBlank { "Model connection failed" })
        return result
    }

    suspend fun detectCapabilities(payload: TestModelSettingsRequest): ModelCapabilityReportDto = request { endpoint ->
        http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/settings/model/detect-capabilities") {
            // 能力检测包含多次顺序请求，外层等待须覆盖全部探测。
            timeout {
                requestTimeoutMillis = (5 * MAX_MODEL_REQUEST_TIMEOUT_SECONDS + 30) * 1000L
                socketTimeoutMillis = requestTimeoutMillis
            }
            setBody(payload)
        }
    }.body()

    suspend fun activate(profileId: String): ModelSettingsDto = request { endpoint ->
        http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/settings/model/profiles/$profileId/activate") {
        }
    }.body()

    suspend fun delete(profileId: String): ModelSettingsDto = request { endpoint ->
        http.client.delete("${endpoint.baseUrl.trimEnd('/')}/api/web/settings/model/profiles/$profileId") {
        }
    }.body()

    private suspend fun request(block: suspend (BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(endpointProvider.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }

}

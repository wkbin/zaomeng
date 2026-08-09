package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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

    suspend fun test(payload: TestModelSettingsRequest): ModelConnectionTestDto {
        val result: ModelConnectionTestDto = request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/settings/model/test") {
                setBody(payload)
            }
        }.body()
        if (!result.ok) throw IllegalStateException(result.message.ifBlank { "Model connection failed" })
        return result
    }

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

package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import top.wkbin.zaomeng.backend.BackendManager

class KtorWorldMemoryClient(
    private val http: KtorHttpClientProvider,
    private val backend: BackendManager,
) {
    suspend fun get(runId: String): WorldMemoryDto = request { endpoint -> http.client.get(base(endpoint, runId)) }.body()

    suspend fun save(runId: String, factId: String, payload: SaveWorldFactRequest): WorldFactDto = request { endpoint ->
        val url = base(endpoint, runId) + "/facts" + factId.takeIf(String::isNotBlank)?.let { "/${it.encodeURLPathPart()}" }.orEmpty()
        if (factId.isBlank()) http.client.post(url) { setBody(payload) } else http.client.put(url) { setBody(payload) }
    }.body()

    suspend fun delete(runId: String, factId: String): DeleteStatusDto = request { endpoint ->
        http.client.delete("${base(endpoint, runId)}/facts/${factId.encodeURLPathPart()}")
    }.body()

    private fun base(endpoint: BackendManager.BackendEndpoint, runId: String) =
        "${endpoint.baseUrl.trimEnd('/')}/api/web/runs/${runId.encodeURLPathPart()}/world-memory"

    private suspend fun request(block: suspend (BackendManager.BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(backend.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }
}

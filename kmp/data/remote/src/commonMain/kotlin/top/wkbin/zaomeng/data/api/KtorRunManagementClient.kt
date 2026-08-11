package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.backend.BackendEndpoint

class KtorRunManagementClient(
    private val http: KtorHttpClientProvider,
    private val endpointProvider: BackendEndpointProvider,
) {
    suspend fun create(payload: CreateRunRequest): RunManifestDto = request { endpoint ->
        http.client.post(url(endpoint, "/api/web/runs")) {
            setBody(payload)
        }
    }.body()

    suspend fun import(payload: ImportRunPackageRequest): RunManifestDto = request { endpoint ->
        http.client.post(url(endpoint, "/api/web/runs/import")) {
            setBody(payload)
        }
    }.body()

    suspend fun get(runId: String): RunManifestDto = request { endpoint ->
        http.client.get(url(endpoint, "/api/web/runs/$runId"))
    }.body()

    suspend fun delete(runId: String): DeleteRunResponse = request { endpoint ->
        http.client.delete(url(endpoint, "/api/web/runs/$runId"))
    }.body()

    suspend fun stop(runId: String): RunManifestDto = request { endpoint ->
        http.client.post(url(endpoint, "/api/web/runs/$runId/stop"))
    }.body()

    private suspend fun request(block: suspend (BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(endpointProvider.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }

    private fun url(endpoint: BackendEndpoint, path: String) =
        endpoint.baseUrl.trimEnd('/') + path

}

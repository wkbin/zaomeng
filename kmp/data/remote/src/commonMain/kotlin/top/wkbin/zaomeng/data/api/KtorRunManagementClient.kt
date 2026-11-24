package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.patch
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
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

    suspend fun previewSillyTavern(payload: SillyTavernPreviewRequest): SillyTavernPreviewDto = request { endpoint ->
        http.client.post(url(endpoint, "/api/web/runs/import/sillytavern/preview")) { setBody(payload) }
    }.body()

    suspend fun listWorldBooks(runId: String): ManagedWorldBooksDto = request { endpoint ->
        http.client.get(url(endpoint, "/api/web/runs/$runId/world-books"))
    }.body()

    suspend fun testWorldBooks(runId: String, input: TestWorldBooksRequest): WorldBookTestDto = request { endpoint ->
        http.client.post(url(endpoint, "/api/web/runs/$runId/world-books/test")) { setBody(input) }
    }.body()

    suspend fun setWorldBookEntry(
        runId: String,
        source: String,
        index: Int,
        payload: SetWorldBookEntryRequest,
    ): ManagedWorldBooksDto = request { endpoint ->
        http.client.patch(url(endpoint, "/api/web/runs/$runId/world-books/$source/entries/$index")) { setBody(payload) }
    }.body()

    suspend fun removeStandaloneWorldBook(runId: String, revision: String): ManagedWorldBooksDto = request { endpoint ->
        http.client.delete(url(endpoint, "/api/web/runs/$runId/world-books/standalone")) { parameter("revision", revision) }
    }.body()

    suspend fun deleteWorldBookEntry(runId: String, source: String, index: Int, revision: String): ManagedWorldBooksDto =
        request { endpoint ->
            http.client.delete(url(endpoint, "/api/web/runs/$runId/world-books/$source/entries/$index")) {
                parameter("revision", revision)
            }
        }.body()

    suspend fun createWorldBookEntry(
        runId: String,
        source: String,
        payload: CreateWorldBookEntryRequest,
    ): ManagedWorldBooksDto = request { endpoint ->
        http.client.post(url(endpoint, "/api/web/runs/$runId/world-books/$source/entries")) { setBody(payload) }
    }.body()

    suspend fun editWorldBookEntry(
        runId: String,
        source: String,
        index: Int,
        payload: EditWorldBookEntryRequest,
    ): ManagedWorldBooksDto = request { endpoint ->
        http.client.patch(url(endpoint, "/api/web/runs/$runId/world-books/$source/entries/$index/content")) { setBody(payload) }
    }.body()

    suspend fun setWorldBookRecursion(
        runId: String,
        source: String,
        payload: SetWorldBookRecursionRequest,
    ): ManagedWorldBooksDto = request { endpoint ->
        http.client.patch(url(endpoint, "/api/web/runs/$runId/world-books/$source/recursion")) { setBody(payload) }
    }.body()

    suspend fun exportSillyTavernCard(runId: String, character: String): HttpResponse = request { endpoint ->
        http.client.get(url(endpoint, "/api/web/runs/${runId.encodeURLPathPart()}/personas/${character.encodeURLPathPart()}/sillytavern-card"))
    }

    suspend fun exportWorldBook(runId: String, source: String): HttpResponse = request { endpoint ->
        http.client.get(url(endpoint, "/api/web/runs/${runId.encodeURLPathPart()}/world-books/${source.encodeURLPathPart()}/export"))
    }

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
            val body = response.bodyAsText()
            throw KtorSessionRequestException(response.status.value, body)
        }
        return response
    }

    private fun url(endpoint: BackendEndpoint, path: String) =
        endpoint.baseUrl.trimEnd('/') + path

}

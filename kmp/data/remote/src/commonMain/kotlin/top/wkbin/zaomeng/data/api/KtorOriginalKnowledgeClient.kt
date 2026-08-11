package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import top.wkbin.zaomeng.backend.BackendEndpoint
import top.wkbin.zaomeng.backend.BackendEndpointProvider

class KtorOriginalKnowledgeClient(
    private val http: KtorHttpClientProvider,
    private val endpointProvider: BackendEndpointProvider,
) {
    suspend fun search(
        runId: String,
        query: String,
        participants: List<String>,
        pinnedOnly: Boolean,
        limit: Int = 50,
    ): OriginalKnowledgeSearchResponse = request { endpoint ->
        http.client.post("${base(endpoint, runId)}/search") {
            setBody(SearchOriginalKnowledgeRequest(query, participants, limit, pinnedOnly))
        }
    }.body()

    suspend fun updateBoundary(runId: String, entryId: String, visibility: String, knowers: List<String>) {
        request { endpoint ->
            http.client.put("${base(endpoint, runId)}/entries/${entryId.encodeURLPathPart()}/boundary") {
                setBody(UpdateOriginalKnowledgeBoundaryRequest(visibility, knowers))
            }
        }.bodyAsText()
    }

    suspend fun updatePinned(runId: String, entryId: String, pinned: Boolean) {
        request { endpoint ->
            http.client.put("${base(endpoint, runId)}/entries/${entryId.encodeURLPathPart()}/pinned") {
                setBody(UpdateOriginalKnowledgePinnedRequest(pinned))
            }
        }.bodyAsText()
    }

    suspend fun rebuild(runId: String) {
        request { endpoint -> http.client.post("${base(endpoint, runId)}/rebuild") }.bodyAsText()
    }

    private fun base(endpoint: BackendEndpoint, runId: String): String =
        "${endpoint.baseUrl.trimEnd('/')}/api/web/runs/${runId.encodeURLPathPart()}/original-knowledge"

    private suspend fun request(block: suspend (BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(endpointProvider.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }
}

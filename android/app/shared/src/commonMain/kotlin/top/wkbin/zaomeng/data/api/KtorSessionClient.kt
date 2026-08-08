package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.backend.BackendEndpoint
import top.wkbin.zaomeng.platform.PlatformLog

class KtorSessionClient(
    private val http: KtorHttpClientProvider,
    private val endpointProvider: BackendEndpointProvider,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun decodeSession(response: HttpResponse): DialogueSessionDto {
        val text = response.bodyAsText()
        return runCatching { json.decodeFromString<DialogueSessionDto>(text) }
            .getOrElse { error ->
                PlatformLog.e(TAG, "Failed to decode DialogueSessionDto. Response body: $text", error)
                throw error
            }
    }
    suspend fun listForRun(runId: String): SessionsResponse = request { endpoint ->
        http.client.get(url(endpoint, "/api/web/runs/$runId/dialogue/sessions"))
    }.body()

    suspend fun listRecent(): SessionsResponse = request { endpoint ->
        http.client.get(url(endpoint, "/api/web/sessions"))
    }.body()

    suspend fun delete(runId: String, sessionId: String): DeleteStatusDto = request { endpoint ->
        http.client.delete(url(endpoint, "/api/web/runs/$runId/dialogue/sessions/$sessionId"))
    }.body()

    suspend fun deleteBatch(payload: DeleteSessionsRequest): DeleteSessionsResponse = request { endpoint ->
        http.client.delete(url(endpoint, "/api/web/sessions")) {
            setBody(payload)
        }
    }.body()

    suspend fun create(runId: String, payload: CreateDialogueSessionRequest): DialogueSessionDto =
        decodeSession(request { endpoint ->
            http.client.post(url(endpoint, "/api/web/runs/$runId/dialogue/sessions")) {
                setBody(payload)
            }
        })

    suspend fun get(runId: String, sessionId: String): DialogueSessionDto =
        decodeSession(request { endpoint ->
            http.client.get(url(endpoint, "/api/web/runs/$runId/dialogue/sessions/$sessionId"))
        })

    suspend fun updateTitle(runId: String, sessionId: String, title: String): DialogueSessionDto =
        decodeSession(request { endpoint ->
            http.client.patch(url(endpoint, "/api/web/runs/$runId/dialogue/sessions/$sessionId/title")) {
                setBody(UpdateDialogueSessionTitleRequest(title))
            }
        })

    private suspend fun request(block: suspend (BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(endpointProvider.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }

    private fun url(endpoint: BackendEndpoint, path: String) = endpoint.baseUrl.trimEnd('/') + path

    private companion object {
        const val TAG = "KtorSessionClient"
    }

}

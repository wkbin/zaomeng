package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.backend.BackendEndpoint
import top.wkbin.zaomeng.client.platform.ClientLog

class KtorSessionRequestException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

class KtorSessionClient(
    private val http: KtorHttpClientProvider,
    private val endpointProvider: BackendEndpointProvider,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun decodeSession(response: HttpResponse): DialogueSessionDto {
        val text = response.bodyAsText()
        return runCatching { json.decodeFromString<DialogueSessionDto>(text) }
            .getOrElse { error ->
                ClientLog.e(TAG, "Failed to decode DialogueSessionDto. Response body: $text", error)
                throw error
            }
    }
    suspend fun listForRun(
        runId: String,
        offset: Int = 0,
        limit: Int = 50,
        query: String = "",
        sort: String = "recent",
    ): SessionsResponse = request { endpoint ->
        http.client.get(url(endpoint, "/api/web/runs/$runId/dialogue/sessions")) {
            pageParameters(offset, limit, query, sort)
        }
    }.body()

    suspend fun listRecent(
        offset: Int = 0,
        limit: Int = 50,
        query: String = "",
        sort: String = "recent",
    ): SessionsResponse = request { endpoint ->
        http.client.get(url(endpoint, "/api/web/sessions")) {
            pageParameters(offset, limit, query, sort)
        }
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

    suspend fun getCardGreetings(runId: String): CardGreetingsDto = request { endpoint ->
        http.client.get(url(endpoint, "/api/web/runs/${runId.encodeURLPathPart()}/dialogue/card-greetings"))
    }.body()

    suspend fun updateCardGreeting(runId: String, payload: UpdateCardGreetingRequest): CardGreetingsDto = request { endpoint ->
        http.client.put(url(endpoint, "/api/web/runs/${runId.encodeURLPathPart()}/dialogue/card-greetings")) {
            setBody(payload)
        }
    }.body()

    suspend fun importSillyTavernChat(runId: String, payload: ImportSillyTavernChatRequest): DialogueSessionDto =
        decodeSession(request { endpoint ->
            http.client.post(url(endpoint, "/api/web/runs/${runId.encodeURLPathPart()}/dialogue/sessions/import-sillytavern")) {
                setBody(payload)
            }
        })

    suspend fun <T> exportSillyTavernChat(runId: String, sessionId: String, consume: suspend (HttpResponse) -> T): T {
        val endpoint = endpointProvider.requireKtorEndpoint()
        val path = "/api/web/runs/${runId.encodeURLPathPart()}/dialogue/sessions/${sessionId.encodeURLPathPart()}/export-sillytavern"
        return http.client.prepareGet(url(endpoint, path)).execute { response ->
            if (response.status.value !in 200..299) {
                throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
            }
            consume(response)
        }
    }

    suspend fun listImportedSwipes(runId: String, sessionId: String, messageIndex: String, offset: Int): ImportedSwipePageDto =
        request { endpoint ->
            val path = "/api/web/runs/${runId.encodeURLPathPart()}/dialogue/sessions/${sessionId.encodeURLPathPart()}/imported-swipes/${messageIndex.encodeURLPathPart()}"
            http.client.get(url(endpoint, path)) {
                parameter("offset", offset)
            }
        }.body()

    suspend fun get(
        runId: String,
        sessionId: String,
        includeTranscript: Boolean = true,
    ): DialogueSessionDto =
        decodeSession(request { endpoint ->
            http.client.get(url(endpoint, "/api/web/runs/$runId/dialogue/sessions/$sessionId")) {
                if (!includeTranscript) parameter("include_transcript", false)
            }
        })

    suspend fun listMessages(
        runId: String,
        sessionId: String,
        offset: Int = 0,
        limit: Int = 100,
        order: String = "asc",
        expectedRevision: Long? = null,
    ): MessagesResponse = request { endpoint ->
        http.client.get(url(endpoint, "/api/web/runs/$runId/dialogue/sessions/$sessionId/messages")) {
            parameter("offset", offset)
            parameter("limit", limit)
            parameter("order", order)
            expectedRevision?.let { parameter("revision", it) }
        }
    }.body()

    suspend fun getProjection(runId: String, sessionId: String): SessionProjectionDto = request { endpoint ->
        http.client.get(url(endpoint, "/api/web/runs/${runId.encodeURLPathPart()}/dialogue/sessions/${sessionId.encodeURLPathPart()}/projection"))
    }.body()

    suspend fun rebuildProjection(runId: String, sessionId: String): SessionProjectionDto = request { endpoint ->
        http.client.post(url(endpoint, "/api/web/runs/${runId.encodeURLPathPart()}/dialogue/sessions/${sessionId.encodeURLPathPart()}/projection/rebuild"))
    }.body()

    suspend fun getOperation(
        runId: String,
        sessionId: String,
        operationId: String,
    ): DialogueOperationStatusResponse = request { endpoint ->
        http.client.get(
            url(
                endpoint,
                "/api/web/runs/${runId.encodeURLPathPart()}/dialogue/sessions/" +
                    "${sessionId.encodeURLPathPart()}/operations/${operationId.encodeURLPathPart()}",
            ),
        )
    }.body()

    suspend fun updateTitle(runId: String, sessionId: String, title: String): DialogueSessionDto =
        decodeSession(request { endpoint ->
            http.client.patch(url(endpoint, "/api/web/runs/$runId/dialogue/sessions/$sessionId/title")) {
                setBody(UpdateDialogueSessionTitleRequest(title))
            }
        })

    suspend fun updateAuthorNote(runId: String, sessionId: String, note: AuthorNoteDto): AuthorNoteDto = request { endpoint ->
        http.client.put(url(endpoint, "/api/web/runs/$runId/dialogue/sessions/$sessionId/author-note")) { setBody(note) }
    }.body()

    private suspend fun request(block: suspend (BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(endpointProvider.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw KtorSessionRequestException(
                statusCode = response.status.value,
                message = "Ktor request failed: ${response.status.value} ${response.bodyAsText()}",
            )
        }
        return response
    }

    private fun url(endpoint: BackendEndpoint, path: String) = endpoint.baseUrl.trimEnd('/') + path

    private fun io.ktor.client.request.HttpRequestBuilder.pageParameters(
        offset: Int,
        limit: Int,
        query: String,
        sort: String,
    ) {
        parameter("offset", offset)
        parameter("limit", limit)
        parameter("sort", sort)
        if (query.isNotBlank()) parameter("q", query)
    }

    private companion object {
        const val TAG = "KtorSessionClient"
    }

}

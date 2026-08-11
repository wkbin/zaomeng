package top.wkbin.zaomeng.data.api

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.backend.BackendEndpoint
import top.wkbin.zaomeng.client.platform.ClientLog

/** 人物关系（对应 server RelationsRoute）。 */
class KtorRelationsClient(
    private val http: KtorHttpClientProvider,
    private val endpointProvider: BackendEndpointProvider,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun get(runId: String): RelationDetailsDto = decodeRelation(
        request { endpoint ->
            http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/relations")
        },
    )

    suspend fun update(runId: String, pairKey: String, request: UpdateRelationDetailRequest): RelationDetailsDto = decodeRelation(
        request { endpoint ->
            http.client.patch("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/relations/$pairKey") {
                setBody(request)
            }
        },
    )

    private suspend fun decodeRelation(response: HttpResponse): RelationDetailsDto {
        val text = response.bodyAsText()
        return runCatching { json.decodeFromString<RelationDetailsDto>(text) }
            .getOrElse { error -> ClientLog.e(TAG, "Failed to decode RelationDetailsDto. Body: $text", error); throw error }
    }

    private suspend fun request(block: suspend (BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(endpointProvider.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }

    private companion object {
        const val TAG = "KtorRelationsClient"
    }
}

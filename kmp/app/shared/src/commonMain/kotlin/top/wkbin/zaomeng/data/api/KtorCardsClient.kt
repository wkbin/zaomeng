package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.backend.BackendEndpoint
import top.wkbin.zaomeng.platform.PlatformLog

class KtorCardsClient(
    private val http: KtorHttpClientProvider,
    private val endpointProvider: BackendEndpointProvider,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun generate(kind: String): ReusableCardDto = decodeCard(
        request { endpoint ->
            http.client.post("${baseUrl(endpoint, kind)}/generate") {
            }
        },
    )

    suspend fun list(kind: String): List<ReusableCardDto> {
        val body = decodeObject(
            request { endpoint ->
                http.client.get(baseUrl(endpoint, kind))
            },
        )
        val items = body["items"]?.jsonArray ?: return emptyList()
        return runCatching { json.decodeFromJsonElement<List<ReusableCardDto>>(items) }
            .getOrElse { error -> PlatformLog.e(TAG, "Failed to decode card list: $body", error); throw error }
    }

    suspend fun get(kind: String, cardId: String): ReusableCardDto = decodeCard(
        request { endpoint ->
            http.client.get("${baseUrl(endpoint, kind)}/$cardId")
        },
    )

    suspend fun save(kind: String, cardId: String, fields: JsonObject): ReusableCardDto = decodeCard(
        request { endpoint ->
            if (cardId.isBlank()) {
                http.client.post(baseUrl(endpoint, kind)) { setBody(fields) }
            } else {
                http.client.put("${baseUrl(endpoint, kind)}/$cardId") { setBody(fields) }
            }
        },
    )

    suspend fun delete(kind: String, cardId: String) {
        request { endpoint ->
            http.client.delete("${baseUrl(endpoint, kind)}/$cardId")
        }
    }

    suspend fun recommend(mode: String, participants: List<String>): JsonObject = decodeObject(
        request { endpoint ->
            http.client.post("${baseUrl(endpoint, "scene")}/recommend") {
                setBody(RecommendSceneCardsRequest(mode = mode, participants = participants))
            }
        },
    )

    /** kind → REST base：scene/self → {kind}-cards；opening → opening-presets。 */
    private fun baseUrl(endpoint: BackendEndpoint, kind: String): String {
        val segment = if (kind == "opening") "opening-presets" else "$kind-cards"
        return "${endpoint.baseUrl.trimEnd('/')}/api/web/$segment"
    }

    private suspend fun decodeObject(response: HttpResponse): JsonObject {
        val text = response.bodyAsText()
        return runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { error -> PlatformLog.e(TAG, "Failed to decode JsonObject. Body: $text", error); throw error }
    }

    private suspend fun decodeCard(response: HttpResponse): ReusableCardDto {
        val text = response.bodyAsText()
        return runCatching { json.decodeFromString<ReusableCardDto>(text) }
            .getOrElse { error -> PlatformLog.e(TAG, "Failed to decode ReusableCardDto. Body: $text", error); throw error }
    }

    private suspend fun request(block: suspend (BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(endpointProvider.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }

    private companion object {
        const val TAG = "KtorCardsClient"
    }
}

package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonObject
import top.wkbin.zaomeng.backend.BackendEndpointProvider
import top.wkbin.zaomeng.backend.BackendEndpoint

class KtorPersonaClient(
    private val http: KtorHttpClientProvider,
    private val endpointProvider: BackendEndpointProvider,
) {
    suspend fun getReview(runId: String, character: String): PersonaReviewDto = request { endpoint ->
        http.client.get(url(endpoint, runId, character))
    }.body()

    suspend fun saveReview(runId: String, character: String, fields: JsonObject): PersonaReviewDto = request { endpoint ->
        http.client.put(url(endpoint, runId, character)) { setBody(fields) }
    }.body()

    suspend fun getQuality(runId: String, character: String): PersonaQualityReportDto = request { endpoint ->
        http.client.get("${url(endpoint, runId, character)}/quality-report")
    }.body()

    suspend fun uploadAvatar(runId: String, character: String, bytes: ByteArray): PersonaAvatarDto = request { endpoint ->
        http.client.post("${url(endpoint, runId, character)}/avatar") {
            setBody(MultiPartFormDataContent(formData {
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=avatar.png")
                })
            }))
        }
    }.body()

    suspend fun getAvatar(runId: String, character: String): ByteArray? {
        val response = http.client.get("${url(endpointProvider.requireKtorEndpoint(), runId, character)}/avatar")
        if (response.status.value == 404) return null
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response.body()
    }

    suspend fun suggestField(runId: String, character: String, field: String): SuggestPersonaFieldResponse = request { endpoint ->
        http.client.post("${url(endpoint, runId, character)}/suggest-field") {
            setBody(SuggestPersonaFieldRequest(field))
        }
    }.body()

    private fun url(endpoint: BackendEndpoint, runId: String, character: String): String =
        "${endpoint.baseUrl.trimEnd('/')}/api/web/runs/${runId.encodeURLPathPart()}/personas/${character.encodeURLPathPart()}"

    private suspend fun request(block: suspend (BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(endpointProvider.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }
}

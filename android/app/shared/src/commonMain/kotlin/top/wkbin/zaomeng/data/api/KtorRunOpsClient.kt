package top.wkbin.zaomeng.data.api

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.decodeFromString
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

/** 运行操作（对应 server RunOperationsRoute）。 */
class KtorRunOpsClient(
    private val http: KtorHttpClientProvider,
    private val endpointProvider: BackendEndpointProvider,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun listBuiltinNovels(): List<BuiltinNovelDto> {
        val body = decodeObject(
            request { endpoint ->
                http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/builtin-novels")
            },
        )
        val items = body["items"]?.jsonArray ?: return emptyList()
        return runCatching { json.decodeFromJsonElement<List<BuiltinNovelDto>>(items) }
            .getOrElse { error -> PlatformLog.e(TAG, "Failed to decode builtin novels: $body", error); throw error }
    }

    suspend fun cloneBuiltinNovel(packageId: String): RunManifestDto = decodeRun(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/builtin-novels/$packageId/clone")
        },
    )

    suspend fun estimateSampling(request: EstimateSamplingRequest): SamplingPlanDto {
        val body = decodeObject(
            request { endpoint ->
                http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/estimate") {
                    setBody(request)
                }
            },
        )
        return runCatching { json.decodeFromJsonElement<SamplingPlanDto>(body) }
            .getOrElse { error -> PlatformLog.e(TAG, "Failed to decode sampling plan: $body", error); throw error }
    }

    suspend fun createCrossoverSpace(request: CreateCrossoverSpaceRequest): RunManifestDto = decodeRun(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/crossover-spaces") {
                setBody(request)
            }
        },
    )

    /** 导出运行书卷包：返回原始字节流。 */
    suspend fun exportRun(runId: String, includeDialogue: Boolean = true): HttpResponse = request { endpoint ->
        http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/export?include_dialogue=$includeDialogue")
    }

    suspend fun refreshRun(runId: String): RunManifestDto = decodeRun(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/refresh")
        },
    )

    suspend fun redistill(runId: String, request: RestartRunRequest): RunManifestDto = decodeRun(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/redistill") {
                setBody(request)
            }
        },
    )

    suspend fun resumeDistill(runId: String): RunManifestDto = decodeRun(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/resume-distill")
        },
    )

    suspend fun suggestRedistill(runId: String, request: SuggestRedistillSegmentsRequest): RedistillSuggestionsDto = decodeRedistill(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/redistill/recommend") {
                setBody(request)
            }
        },
    )

    private suspend fun decodeObject(response: HttpResponse): JsonObject {
        val text = response.bodyAsText()
        return runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { error -> PlatformLog.e(TAG, "Failed to decode JsonObject. Body: $text", error); throw error }
    }

    private suspend fun decodeRun(response: HttpResponse): RunManifestDto {
        val text = response.bodyAsText()
        return runCatching { json.decodeFromString<RunManifestDto>(text) }
            .getOrElse { error -> PlatformLog.e(TAG, "Failed to decode RunManifestDto. Body: $text", error); throw error }
    }

    private suspend fun decodeRedistill(response: HttpResponse): RedistillSuggestionsDto {
        val text = response.bodyAsText()
        return runCatching { json.decodeFromString<RedistillSuggestionsDto>(text) }
            .getOrElse { error -> PlatformLog.e(TAG, "Failed to decode RedistillSuggestionsDto. Body: $text", error); throw error }
    }

    private suspend fun request(block: suspend (BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(endpointProvider.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }

    private companion object {
        const val TAG = "KtorRunOpsClient"
    }
}

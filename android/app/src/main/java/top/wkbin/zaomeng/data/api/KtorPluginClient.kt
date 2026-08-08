package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import top.wkbin.zaomeng.backend.BackendManager
import android.util.Log

class KtorPluginClient(
    private val http: KtorHttpClientProvider,
    private val backend: BackendManager,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    suspend fun list(): PluginsResponse = request { endpoint ->
        http.client.get(url(endpoint, "/api/web/plugins"))
    }.body()

    suspend fun refresh(): PluginsResponse = request { endpoint ->
        http.client.post(url(endpoint, "/api/web/plugins/refresh"))
    }.body()

    suspend fun enable(pluginId: String): PluginDto = setEnabled(pluginId, true)
    suspend fun disable(pluginId: String): PluginDto = setEnabled(pluginId, false)

    private suspend fun setEnabled(pluginId: String, enabled: Boolean): PluginDto = request { endpoint ->
        val action = if (enabled) "enable" else "disable"
        http.client.post(url(endpoint, "/api/web/plugins/$pluginId/$action"))
    }.body()

    suspend fun config(pluginId: String): PluginConfigResponse = request { endpoint ->
        http.client.get(url(endpoint, "/api/web/plugins/$pluginId/config"))
    }.body()

    suspend fun updateConfig(pluginId: String, config: JsonObject): PluginConfigResponse = request { endpoint ->
        http.client.put(url(endpoint, "/api/web/plugins/$pluginId/config")) {
            setBody(UpdatePluginConfigRequest(config))
        }
    }.body()

    suspend fun logs(pluginId: String): PluginLogsResponse = request { endpoint ->
        http.client.get(url(endpoint, "/api/web/plugins/$pluginId/logs"))
    }.body()

    suspend fun uninstall(pluginId: String): UninstallPluginResponse = request { endpoint ->
        http.client.delete(url(endpoint, "/api/web/plugins/$pluginId"))
    }.body()

    suspend fun inspect(request: InspectPluginPackageRequest): PluginPackageInspectionDto = decode(
        request { endpoint ->
            http.client.post(url(endpoint, "/api/web/plugins/packages/inspect")) { setBody(request) }
        },
    )

    suspend fun install(token: String, request: InstallPluginPackageRequest): PluginDto = decode(
        request { endpoint ->
            http.client.post(url(endpoint, "/api/web/plugins/packages/$token/install")) { setBody(request) }
        },
    )

    suspend fun chatAction(
        runId: String,
        sessionId: String,
        pluginId: String,
        actionId: String,
        request: PluginChatActionRequest,
    ): PluginChatActionResponse = decode(
        request { endpoint ->
            http.client.post(url(endpoint, "/api/web/runs/$runId/dialogue/sessions/$sessionId/plugins/$pluginId/actions/$actionId")) {
                setBody(request)
            }
        },
    )

    suspend fun temporaryNpcGenerator(
        runId: String,
        sessionId: String,
        pluginId: String,
        generatorId: String,
        request: PluginTemporaryNpcGeneratorRequest,
    ): PluginTemporaryNpcGeneratorResponse = decode(
        request { endpoint ->
            http.client.post(url(endpoint, "/api/web/runs/$runId/dialogue/sessions/$sessionId/plugins/$pluginId/npc-generators/$generatorId")) {
                setBody(request)
            }
        },
    )

    suspend fun setEnhancerState(
        runId: String,
        sessionId: String,
        pluginId: String,
        enhancerId: String,
        enabled: Boolean,
    ): DialogueSessionDto = decode(
        request { endpoint ->
            http.client.put(url(endpoint, "/api/web/runs/$runId/dialogue/sessions/$sessionId/plugins/$pluginId/enhancers/$enhancerId/state")) {
                setBody(SetGenerationEnhancerStateRequest(enabled))
            }
        },
    )

    private suspend inline fun <reified T> decode(response: HttpResponse): T {
        val text = response.bodyAsText()
        return runCatching { json.decodeFromString<T>(text) }
            .getOrElse { error -> Log.e(TAG, "Failed to decode response. Body: $text", error); throw error }
    }

    private suspend fun request(block: suspend (BackendManager.BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(backend.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }

    private fun url(endpoint: BackendManager.BackendEndpoint, path: String) =
        endpoint.baseUrl.trimEnd('/') + path

    private companion object {
        const val TAG = "KtorPluginClient"
    }
}

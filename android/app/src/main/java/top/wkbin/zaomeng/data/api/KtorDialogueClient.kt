package top.wkbin.zaomeng.data.api

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.wkbin.zaomeng.backend.BackendManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import android.util.Log

class KtorDialogueClient(
    private val http: KtorHttpClientProvider,
    private val backend: BackendManager,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 流式专用 OkHttp 客户端：readTimeout 对齐 HttpTimeout 的 5 分钟（SSE 空闲期不掐断）。 */
    private val streamingHttp = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun reply(runId: String, sessionId: String, payload: DialogueReplyRequest): DialogueSessionDto = decodeSession(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/dialogue/sessions/$sessionId/reply") {
                setBody(payload)
            }
        },
    )

    /** 流式回复（SSE）：走 OkHttp 原生流式读取，返回 okhttp3.Response（调用方用 body.source() 逐行解析）。
     *  Ktor client 的 bodyAsChannel 在 Android + OkHttp 引擎实测会把整个响应体缓冲到连接关闭才返回，
     *  导致 SSE 一次性到达；OkHttp 原生 ResponseBody.source() 是确定逐块流式。 */
    suspend fun streamReply(runId: String, sessionId: String, payload: DialogueReplyRequest): okhttp3.Response {
        val endpoint = backend.requireKtorEndpoint()
        val url = "${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/dialogue/sessions/$sessionId/reply/stream"
        val requestBody = http.json.encodeToString(DialogueReplyRequest.serializer(), payload)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Authorization", "Bearer ${http.bearerToken()}")
            .build()
        return withContext(Dispatchers.IO) {
            streamingHttp.newCall(request).execute()
        }
    }

    // ------------------------------------------------------------------
    // 对话高级功能（对应 server DialogueAdvancedRoute）
    // ------------------------------------------------------------------

    suspend fun searchSession(runId: String, sessionId: String, query: String, limit: Int): List<ChatSearchResultDto> {
        val body = decodeObject(
            request { endpoint ->
                http.client.get(
                    "${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/dialogue/sessions/$sessionId/search" +
                        "?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=$limit",
                )
            },
        )
        val items = body["items"] ?: error("search response missing items")
        return runCatching { json.decodeFromJsonElement<List<ChatSearchResultDto>>(items) }
            .getOrElse { error -> Log.e(TAG, "Failed to decode search items: $body", error); throw error }
    }

    suspend fun recoverSession(runId: String, sessionId: String, force: Boolean): DialogueSessionDto = decodeSession(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/dialogue/sessions/$sessionId/recover?force=$force")
        },
    )

    suspend fun suggestReply(runId: String, sessionId: String, seedText: String, direction: String): String {
        val body = decodeObject(
            request { endpoint ->
                http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/dialogue/sessions/$sessionId/suggest") {
                    setBody(DialogueSuggestionRequest(seedText = seedText, direction = direction))
                }
            },
        )
        return body["suggestion"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    suspend fun correctLatest(runId: String, sessionId: String): DialogueSessionDto = decodeSession(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/dialogue/sessions/$sessionId/correct-latest")
        },
    )

    suspend fun deepReview(runId: String, sessionId: String): DialogueSessionDto = decodeSession(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/dialogue/sessions/$sessionId/deep-review")
        },
    )

    suspend fun directorOptions(runId: String, sessionId: String, goal: String, action: String): JsonObject = decodeObject(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/dialogue/sessions/$sessionId/director-options") {
                setBody(DialogueDirectorRequest(goal = goal, action = action))
            }
        },
    )

    suspend fun branchFromTurn(runId: String, sessionId: String, turnId: String): DialogueSessionDto = decodeSession(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/dialogue/sessions/$sessionId/branch-turn") {
                setBody(BranchDialogueTurnRequest(turnId))
            }
        },
    )

    suspend fun branchFromScene(runId: String, sessionId: String, sceneIndex: Int): DialogueSessionDto = decodeSession(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/dialogue/sessions/$sessionId/branch") {
                setBody(BranchDialogueSceneRequest(sceneIndex))
            }
        },
    )

    suspend fun updateBranchMeta(
        runId: String,
        sessionId: String,
        label: String?,
        isMainline: Boolean?,
        lockedEventIds: List<String>?,
    ): DialogueSessionDto = decodeSession(
        request { endpoint ->
            http.client.patch("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/dialogue/sessions/$sessionId/branch-meta") {
                setBody(UpdateDialogueBranchMetaRequest(label = label, isMainline = isMainline, lockedEventIds = lockedEventIds))
            }
        },
    )

    suspend fun setRelationLock(runId: String, sessionId: String, pairKey: String, locked: Boolean): DialogueSessionDto = decodeSession(
        request { endpoint ->
            http.client.put("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/dialogue/sessions/$sessionId/relation-lock") {
                setBody(UpdateDialogueRelationLockRequest(pairKey = pairKey, locked = locked))
            }
        },
    )

    suspend fun saveMemory(
        runId: String,
        sessionId: String,
        memoryId: String,
        text: String,
        category: String,
        pinned: Boolean,
        enabled: Boolean,
    ): DialogueSessionDto {
        val base = "${backend.requireKtorEndpoint().baseUrl.trimEnd('/')}/api/web/runs/$runId/dialogue/sessions/$sessionId/memories"
        val payload = UpsertDialogueMemoryRequest(text = text, category = category, pinned = pinned, enabled = enabled)
        val response = request { endpoint ->
            if (memoryId.isBlank()) {
                http.client.post(base) { setBody(payload) }
            } else {
                http.client.put("$base/$memoryId") { setBody(payload) }
            }
        }
        return decodeSession(response)
    }

    suspend fun deleteMemory(runId: String, sessionId: String, memoryId: String): DialogueSessionDto = decodeSession(
        request { endpoint ->
            http.client.delete("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/dialogue/sessions/$sessionId/memories/$memoryId")
        },
    )

    suspend fun switchScene(
        runId: String,
        sessionId: String,
        sceneCardId: String,
        transitionMessage: String,
        autoContinue: Boolean,
    ): DialogueSessionDto = decodeSession(
        request { endpoint ->
            http.client.put("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/dialogue/sessions/$sessionId/scene-card") {
                setBody(SwitchDialogueSceneRequest(sceneCardId = sceneCardId, transitionMessage = transitionMessage, autoContinue = autoContinue))
            }
        },
    )

    suspend fun recommendScene(runId: String, sessionId: String): JsonObject = decodeObject(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/dialogue/sessions/$sessionId/scene-card/recommend")
        },
    )

    private suspend fun decodeObject(response: HttpResponse): JsonObject {
        val text = response.bodyAsText()
        return runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { error -> Log.e(TAG, "Failed to decode JsonObject. Body: $text", error); throw error }
    }

    private suspend fun decodeSession(response: HttpResponse): DialogueSessionDto {
        val text = response.bodyAsText()
        return runCatching { json.decodeFromString<DialogueSessionDto>(text) }
            .getOrElse { error -> Log.e(TAG, "Failed to decode DialogueSessionDto. Body: $text", error); throw error }
    }

    private suspend fun request(block: suspend (BackendManager.BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(backend.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }

    private companion object {
        const val TAG = "KtorDialogueClient"
    }
}

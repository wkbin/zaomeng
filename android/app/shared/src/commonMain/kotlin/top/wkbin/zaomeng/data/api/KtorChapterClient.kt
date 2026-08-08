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

class KtorChapterClient(
    private val http: KtorHttpClientProvider,
    private val endpointProvider: BackendEndpointProvider,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun list(runId: String): ChaptersResponse = request { endpoint ->
        http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/chapters") {
        }
    }.body()

    /** 创建/更新章节（对应 server PUT/POST /chapters）。 */
    suspend fun save(runId: String, chapterId: String, payload: SaveChapterRequest): ChapterDto {
        val base = "${endpointProvider.requireKtorEndpoint().baseUrl.trimEnd('/')}/api/web/runs/$runId/chapters"
        val response = request { endpoint ->
            if (chapterId.isBlank()) {
                http.client.post(base) { setBody(payload) }
            } else {
                http.client.put("$base/$chapterId") { setBody(payload) }
            }
        }
        return decodeChapter(response)
    }

    /** 会话归档为章节。 */
    suspend fun archiveSession(runId: String, request: ArchiveDialogueChapterRequest): ChapterDto = decodeChapter(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/chapters/archive-session") {
                setBody(request)
            }
        },
    )

    /** 会话转章节（对应 ZaomengApi.convertSessionAsNovel 端点）。 */
    suspend fun convertSessionAsNovel(runId: String, request: ArchiveDialogueChapterRequest): ChapterDto = decodeChapter(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/chapters/convert-session") {
                setBody(request)
            }
        },
    )

    suspend fun delete(runId: String, chapterId: String): JsonObject = decodeObject(
        request { endpoint ->
            http.client.delete("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/chapters/$chapterId")
        },
    )

    /** 从章节继续写作：返回续写会话。 */
    suspend fun continueWriting(runId: String, chapterId: String): DialogueSessionDto = decodeSession(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/chapters/$chapterId/continue")
        },
    )

    /** 同步章节会话。 */
    suspend fun syncSession(runId: String, chapterId: String): ChapterDto = decodeChapter(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/chapters/$chapterId/sync-session")
        },
    )

    /** 章节排序。 */
    suspend fun reorder(runId: String, chapterId: String, targetOrder: Int): List<ChapterDto> {
        val body = decodeObject(
            request { endpoint ->
                http.client.patch("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/chapters/$chapterId/order") {
                    setBody(ReorderChapterRequest(targetOrder))
                }
            },
        )
        val items = body["items"]?.jsonArray ?: return emptyList()
        return runCatching { json.decodeFromJsonElement<List<ChapterDto>>(items) }
            .getOrElse { error -> PlatformLog.e(TAG, "Failed to decode reordered chapters: $body", error); throw error }
    }

    /** 导出章节手稿：返回原始响应（文本/文件字节流）。 */
    suspend fun export(runId: String, format: String): HttpResponse = request { endpoint ->
        http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/chapters/export?format=$format")
    }

    /** 全书内容搜索（对应 server ChapterManagementRoute GET /search）。 */
    suspend fun search(runId: String, query: String): List<SearchResultDto> {
        val body = decodeObject(
            request { endpoint ->
                http.client.get("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}")
            },
        )
        val items = body["items"]?.jsonArray ?: return emptyList()
        return runCatching { json.decodeFromJsonElement<List<SearchResultDto>>(items) }
            .getOrElse { error -> PlatformLog.e(TAG, "Failed to decode search results: $body", error); throw error }
    }

    /** 向书卷提问（对应 server ChapterManagementRoute POST /ask）。 */
    suspend fun ask(runId: String, question: String): AskBookResponseDto = decodeAsk(
        request { endpoint ->
            http.client.post("${endpoint.baseUrl.trimEnd('/')}/api/web/runs/$runId/ask") {
                setBody(AskBookQuestionRequest(question))
            }
        },
    )

    private suspend fun decodeObject(response: HttpResponse): JsonObject {
        val text = response.bodyAsText()
        return runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { error -> PlatformLog.e(TAG, "Failed to decode JsonObject. Body: $text", error); throw error }
    }

    private suspend fun decodeChapter(response: HttpResponse): ChapterDto {
        val text = response.bodyAsText()
        return runCatching { json.decodeFromString<ChapterDto>(text) }
            .getOrElse { error -> PlatformLog.e(TAG, "Failed to decode ChapterDto. Body: $text", error); throw error }
    }

    private suspend fun decodeSession(response: HttpResponse): DialogueSessionDto {
        val text = response.bodyAsText()
        return runCatching { json.decodeFromString<DialogueSessionDto>(text) }
            .getOrElse { error -> PlatformLog.e(TAG, "Failed to decode DialogueSessionDto. Body: $text", error); throw error }
    }

    private suspend fun decodeAsk(response: HttpResponse): AskBookResponseDto {
        val text = response.bodyAsText()
        return runCatching { json.decodeFromString<AskBookResponseDto>(text) }
            .getOrElse { error -> PlatformLog.e(TAG, "Failed to decode AskBookResponseDto. Body: $text", error); throw error }
    }

    private suspend fun request(block: suspend (BackendEndpoint) -> HttpResponse): HttpResponse {
        val response = block(endpointProvider.requireKtorEndpoint())
        if (response.status.value !in 200..299) {
            throw IllegalStateException("Ktor request failed: ${response.status.value} ${response.bodyAsText()}")
        }
        return response
    }

    private companion object {
        const val TAG = "KtorChapterClient"
    }
}

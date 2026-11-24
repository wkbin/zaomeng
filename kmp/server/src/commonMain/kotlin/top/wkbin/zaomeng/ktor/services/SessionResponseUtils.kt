package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import top.wkbin.zaomeng.data.api.AuthorNoteDto
import top.wkbin.zaomeng.data.api.MessagesResponse
import top.wkbin.zaomeng.data.api.OriginalKnowledgeEntryDto
import top.wkbin.zaomeng.data.api.TranscriptItemDto
import top.wkbin.zaomeng.data.api.WorldBookMatchDto

/** 会话响应统一带上 transcript_count（轻量/全量响应均携带）。 */
fun withTranscriptCount(session: JsonObject): JsonObject = buildJsonObject {
    session.forEach { (key, value) ->
        when {
            key == WorldBookTiming.FIELD -> Unit
            key == "transcript" && value is JsonArray -> put(key, JsonArray(value.map {
                (it as? JsonObject)?.withoutWorldBookTimingDelta() ?: it
            }))
            else -> put(key, value)
        }
    }
    put("transcript_count", transcriptSize(session))
    put("revision", sessionHistoryRevision(session))
}

/** 轻量会话：去掉 transcript 大字段，保留 transcript_count，供聊天页增量模型使用。 */
fun leanSession(session: JsonObject): JsonObject = buildJsonObject {
    session.forEach { (key, value) ->
        if (key != "transcript" && key != WorldBookTiming.FIELD) put(key, value)
    }
    put("transcript_count", transcriptSize(session))
    put("revision", sessionHistoryRevision(session))
}

internal fun JsonObject.withoutWorldBookTimingDelta(): JsonObject =
    if (WorldBookTiming.DELTA in this) JsonObject(this - WorldBookTiming.DELTA) else this

fun transcriptSize(session: JsonObject): Int =
    (session["transcript"] as? JsonArray)?.let { transcript ->
        (session["transcript_start"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0) + transcript.size
    } ?: session["transcript_count"]?.jsonPrimitive?.intOrNull
    ?: 0

fun transcriptOf(session: JsonObject): List<JsonObject> =
    (session["transcript"] as? JsonArray)
        ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        .orEmpty()

internal fun JsonObject.sessionAuthorNote(): AuthorNoteDto? =
    (get("author_note") as? JsonObject)?.let {
        runCatching { Json.decodeFromJsonElement<AuthorNoteDto>(it) }.getOrNull()
    }

/** 按 turn_id 提取某轮新增的 transcript 条目（幂等重放时同样能取到已提交的条目）。 */
fun transcriptByTurnId(session: JsonObject, turnId: String): List<JsonObject> {
    if (turnId.isBlank()) return emptyList()
    return transcriptOf(session).filter { item ->
        item["turn_id"]?.jsonPrimitive?.contentOrNull == turnId
    }
}

/**
 * 会话消息分页：order=desc 时 offset 表示「跳过最新 N 条」，返回更早的一页（新→旧），
 * 客户端反转后向上追加；order=asc 时从开头正序切片（用于增量补齐）。
 */
fun pageTranscript(
    session: JsonObject,
    offset: Int,
    limit: Int,
    order: String,
): MessagesResponse {
    val transcript = transcriptOf(session)
    val total = transcript.size
    val normalizedOffset = offset.coerceAtLeast(0)
    val normalizedLimit = limit.coerceIn(1, 500)
    val items = if (order == "desc") {
        transcript.mapIndexed { index, item -> index to item }.asReversed()
            .drop(normalizedOffset).take(normalizedLimit).map { (seq, item) ->
                toTranscriptItemDto(item, seq)
            }
    } else {
        transcript.drop(normalizedOffset).take(normalizedLimit).mapIndexed { index, item ->
            toTranscriptItemDto(item, normalizedOffset + index)
        }
    }
    val hasMore = normalizedOffset + items.size < total
    return MessagesResponse(
        items = items,
        total = total,
        hasMore = hasMore,
        revision = sessionHistoryRevision(session),
        nextOffset = if (hasMore) normalizedOffset + items.size else null,
    )
}

fun toMessagesResponse(page: SessionTranscriptPage): MessagesResponse = MessagesResponse(
    items = page.items.map { entry -> toTranscriptItemDto(entry.value, entry.seq) },
    total = page.total,
    hasMore = page.hasMore,
    revision = page.revision,
    nextOffset = page.nextOffset,
)

fun toTranscriptItemDto(item: JsonObject, seq: Int = item["seq"]?.jsonPrimitive?.intOrNull ?: -1): TranscriptItemDto = TranscriptItemDto(
    seq = seq,
    sillyTavernMessageIndex = (item["sillytavern_message_index"] as? JsonPrimitive)?.contentOrNull,
    sillyTavernSwipeCount = (item["sillytavern_swipe_count"] as? JsonPrimitive)?.intOrNull,
    speaker = item.stringValue("speaker"),
    message = item.stringValue("message"),
    innerThought = item.stringValue("inner_thought"),
    role = item.stringValue("role"),
    turnId = item.stringValue("turn_id"),
    timestamp = item.stringValue("timestamp"),
    evidence = item["evidence"]?.jsonArray.orEmpty().mapNotNull { evidence ->
        runCatching {
            sessionResponseJson.decodeFromJsonElement<OriginalKnowledgeEntryDto>(evidence)
        }.getOrNull()
    },
    worldBookMatches = (item["world_book_matches"] as? JsonArray).orEmpty().take(4).mapNotNull { match ->
        runCatching { sessionResponseJson.decodeFromJsonElement<WorldBookMatchDto>(match) }.getOrNull()
    },
)

private val sessionResponseJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.stringValue(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

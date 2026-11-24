package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class HistoryLookup(
    val revision: Long,
    val items: List<TranscriptEntry>,
    val complete: Boolean,
)

/** 有界、只读的 history_search/history_read 与可选 JSON 检索规划。 */
class DialogueHistoryRetrievalService(
    private val storage: StorageService,
    private val llm: LlmClient? = null,
    private val prompts: PromptLoader? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun historySearch(
        runId: String,
        sessionId: String,
        query: String,
        order: TranscriptOrder = TranscriptOrder.DESC,
        limit: Int = 3,
        expectedRevision: Long,
    ): HistoryLookup {
        val tokens = query.split(Regex("\\s+")).map { it.trim().lowercase() }.filter(String::isNotBlank).distinct()
        if (tokens.isEmpty()) return HistoryLookup(expectedRevision, emptyList(), complete = true)
        val matches = mutableListOf<Pair<Int, TranscriptEntry>>()
        var offset = 0
        var pages = 0
        var fullyScanned = false
        while (pages < MAX_SEARCH_PAGES && matches.size < limit) {
            val page = storage.transcriptReader.readPage(
                runId,
                sessionId,
                offset,
                SEARCH_PAGE_SIZE,
                order,
                expectedRevision,
            )
            page.items.forEach { entry ->
                val speaker = entry.value["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
                val message = entry.value["message"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
                val haystack = "$speaker\n$message"
                val score = tokens.sumOf { token -> if (haystack.contains(token)) 1 else 0 } +
                    if (haystack.contains(tokens.joinToString(" "))) 3 else 0
                if (score > 0) matches += score to entry
            }
            pages++
            if (!page.hasMore || page.nextOffset == null) {
                fullyScanned = true
                break
            }
            offset = requireNotNull(page.nextOffset)
        }
        val ordered = if (order == TranscriptOrder.DESC) {
            matches.sortedWith(compareByDescending<Pair<Int, TranscriptEntry>> { it.first }
                .thenByDescending { it.second.seq })
        } else {
            matches.sortedWith(compareByDescending<Pair<Int, TranscriptEntry>> { it.first }
                .thenBy { it.second.seq })
        }
        return HistoryLookup(
            revision = expectedRevision,
            items = ordered.take(limit).map { it.second },
            complete = fullyScanned,
        )
    }

    fun historyRead(
        runId: String,
        sessionId: String,
        turnId: String,
        expectedRevision: Long,
    ): HistoryLookup {
        val safeTurnId = PathSafety.validateStorageId(turnId, "turn_id")
        val matches = mutableListOf<TranscriptEntry>()
        var offset = 0
        var pages = 0
        var fullyScanned = false
        while (pages < MAX_READ_PAGES) {
            val page = readPage(runId, sessionId, offset, expectedRevision)
            matches += page.items.filter { it.value["turn_id"]?.jsonPrimitive?.contentOrNull == safeTurnId }
            pages++
            if (matches.isNotEmpty()) {
                // DESC 分页下同一 turn 可能跨页：向前多读一页，避免只返回后半段。
                val nextOffset = page.nextOffset
                if (page.hasMore && nextOffset != null && pages < MAX_READ_PAGES) {
                    matches += readPage(runId, sessionId, nextOffset, expectedRevision).items.filter {
                        it.value["turn_id"]?.jsonPrimitive?.contentOrNull == safeTurnId
                    }
                }
                break
            }
            if (!page.hasMore || page.nextOffset == null) {
                fullyScanned = true
                break
            }
            offset = requireNotNull(page.nextOffset)
        }
        return HistoryLookup(
            revision = expectedRevision,
            items = matches.distinctBy(TranscriptEntry::seq).sortedBy(TranscriptEntry::seq),
            complete = fullyScanned,
        )
    }

    private fun readPage(
        runId: String,
        sessionId: String,
        offset: Int,
        expectedRevision: Long,
    ): SessionTranscriptPage = storage.transcriptReader.readPage(
        runId,
        sessionId,
        offset,
        READ_PAGE_SIZE,
        TranscriptOrder.DESC,
        expectedRevision,
    )

    suspend fun enrichHistory(
        runId: String,
        sessionId: String,
        turnId: String,
        message: String,
        baseRevision: Long,
        sessionManifest: JsonObject,
        payload: Map<String, Any?>,
    ): Map<String, Any?> {
        val history = (payload["history"] as? List<*>).orEmpty()
        val memoryContext = (payload["memory_context"] as? Map<*, *>).orEmpty()
        val anchors = (memoryContext["retrieved_memories"] as? List<*>).orEmpty().take(3)
        if (history.size > FINAL_HISTORY_SIZE || anchors.isNotEmpty()) {
            if (!hasHistoryHint(message)) return payload
        } else {
            return payload
        }
        return try {
            val calls = plan(runId, message, baseRevision, history, anchors, memoryContext)
            if (calls.isEmpty()) return payload
            val currentTurnIds = history.mapNotNull {
                (it as? Map<*, *>)?.get("turn_id")?.toString()
            }.toSet() + turnId
            val currentSeqs = history.mapIndexedNotNull { index, item ->
                (item as? Map<*, *>)?.get("seq")?.toString()?.toIntOrNull()
                    ?: run {
                        val recentSize = (sessionManifest["transcript"] as? JsonArray).orEmpty().size
                        val firstVisibleSeq = sessionTranscriptStart(sessionManifest) +
                            (recentSize - history.size).coerceAtLeast(0)
                        firstVisibleSeq + index
                    }
            }.toSet()
            val evidence = linkedMapOf<Int, Pair<TranscriptEntry, Boolean>>()
            var complete = true
            var searches = 0
            var reads = 0
            calls.forEach { call ->
                when (call.first) {
                    "history_search" -> if (searches < 2) {
                        val result = historySearch(
                            runId,
                            sessionId,
                            call.second,
                            if (call.third == "asc") TranscriptOrder.ASC else TranscriptOrder.DESC,
                            3,
                            baseRevision,
                        )
                        complete = complete && result.complete
                        result.items.forEach { entry ->
                            if (entry.seq !in currentSeqs && entry.value["turn_id"]?.jsonPrimitive?.contentOrNull !in currentTurnIds) {
                                evidence[entry.seq] = entry to false
                            }
                        }
                        searches++
                    }
                    "history_read" -> if (reads < 2) {
                        val result = historyRead(runId, sessionId, call.second, baseRevision)
                        complete = complete && result.complete
                        result.items.forEach { entry ->
                            if (entry.seq !in currentSeqs && entry.value["turn_id"]?.jsonPrimitive?.contentOrNull !in currentTurnIds) {
                                evidence[entry.seq] = entry to true
                            }
                        }
                        reads++
                    }
                }
            }
            val evidenceJson = buildEvidence(evidence, complete)
            if (evidenceJson.isEmpty()) payload else payload + ("history_evidence" to evidenceJson)
        } catch (_: TimeoutCancellationException) {
            payload
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (conflict: SessionRevisionConflictException) {
            throw conflict
        } catch (_: Exception) {
            payload
        }
    }

    private suspend fun plan(
        runId: String,
        message: String,
        revision: Long,
        history: List<*>,
        anchors: List<*>,
        memoryContext: Map<*, *>,
    ): List<Triple<String, String, String>> {
        val client = llm ?: return emptyList()
        val system = prompts?.loadRawPrompt("dialogue/history_retrieval.md")?.takeIf(String::isNotBlank)
            ?: return emptyList()
        val input = buildJsonObject {
            put("source_revision", revision)
            put("message", message.take(600))
            put("current_history", buildJsonArray {
                history.takeLast(FINAL_HISTORY_SIZE).forEach { raw ->
                    val item = raw as? Map<*, *> ?: return@forEach
                    add(buildJsonObject {
                        put("seq", item["seq"]?.toString().orEmpty())
                        put("turn_id", item["turn_id"]?.toString().orEmpty())
                        put("speaker", item["speaker"]?.toString().orEmpty())
                        put("message", item["message"]?.toString().orEmpty().take(240))
                    })
                }
            })
            put("memory_anchors", buildJsonArray {
                anchors.forEach { raw ->
                    val item = raw as? Map<*, *> ?: return@forEach
                    add(buildJsonObject {
                        put("turn_id", item["turn_id"]?.toString().orEmpty())
                        put("text", item["text"]?.toString().orEmpty().take(240))
                    })
                }
            })
            put("session_summary", memoryContext["session_summary"]?.toString().orEmpty().take(800))
            put("archived_summary", memoryContext["archived_summary"]?.toString().orEmpty().take(800))
        }
        return withTimeout(6_000) {
            val response = client.chatCompletion(
                messages = listOf(
                    LlmClient.ChatMessage("system", system),
                    LlmClient.ChatMessage("user", input.toString()),
                ),
                model = storage.loadModelSettings()["model"] as? String ?: "gpt-4",
                temperature = 0.0,
                maxTokens = 512,
                requireJsonObject = true,
            )
            parseCalls(response.choices.firstOrNull()?.message?.content.orEmpty())
        }
    }

    private fun parseCalls(content: String): List<Triple<String, String, String>> {
        val normalized = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = runCatching { json.parseToJsonElement(normalized).jsonObject }.getOrNull() ?: return emptyList()
        val calls = (root["calls"] as? JsonArray) ?: return emptyList()
        return calls.mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            when (item["tool"]?.jsonPrimitive?.contentOrNull) {
                "history_search" -> {
                    val query = item["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (query.isBlank() || query.length > 120) {
                        null
                    } else {
                        val order = item["order"]?.jsonPrimitive?.contentOrNull ?: "desc"
                        if (order !in setOf("asc", "desc")) null else Triple("history_search", query, order)
                    }
                }
                "history_read" -> {
                    val turnId = item["turn_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (turnId.isBlank() || turnId.length > 160) null else Triple("history_read", turnId, "")
                }
                else -> null
            }
        }.distinctBy { it }
    }

    private fun buildEvidence(
        entries: Map<Int, Pair<TranscriptEntry, Boolean>>,
        complete: Boolean,
    ): List<Map<String, Any?>> {
        var remaining = MAX_EVIDENCE_CHARS
        // 不用 toSortedMap：它是 JVM 专有扩展，iOS/Native 目标没有。
        return entries.entries.sortedBy { it.key }.mapNotNull { (_, payload) ->
            val (entry, fullTurn) = payload
            if (remaining <= 0) return@mapNotNull null
            val rawMessage = entry.value["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val text = rawMessage.take(remaining)
            remaining -= text.length
            buildMap<String, Any?> {
                put("seq", entry.seq)
                put("turn_id", entry.value["turn_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("speaker", entry.value["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("role", entry.value["role"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("message", text)
                entry.value["timestamp"]?.let { put("timestamp", it) }
                if (text.length < rawMessage.length) put("truncated", true)
                if (!complete) put("incomplete_scan", true)
                if (fullTurn) put("full_turn", true)
            }
        }.take(MAX_EVIDENCE_ITEMS)
    }

    private fun hasHistoryHint(message: String): Boolean = HISTORY_HINTS.any { it in message }

    private companion object {
        const val FINAL_HISTORY_SIZE = 6
        const val MAX_SEARCH_PAGES = 6
        const val SEARCH_PAGE_SIZE = 200
        const val MAX_READ_PAGES = 4
        const val READ_PAGE_SIZE = 200
        const val MAX_EVIDENCE_ITEMS = 8
        const val MAX_EVIDENCE_CHARS = 4_000
        val HISTORY_HINTS = listOf(
            "原话", "当时", "上次", "第一次", "最早", "之前", "还记得", "约定", "承诺", "说过", " earlier", "last time",
        )
    }
}

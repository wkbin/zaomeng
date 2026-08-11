package top.wkbin.zaomeng.ktor.services

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
import top.wkbin.zaomeng.platform.SimpleLock
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.data.api.DialogueMemoryDto
import top.wkbin.zaomeng.data.api.MemoryQualityReportDto

/** 本地持久化长期记忆：以 lexical retrieval 替代 1.5 的可选 Pinecone。 */
class LongTermMemoryService(private val storage: StorageService) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    private fun lockFor(runId: String, sessionId: String): SimpleLock = locksLock.withLock {
        locks.getOrPut("$runId:$sessionId") { SimpleLock() }
    }

    fun appendTurn(
        runId: String,
        sessionId: String,
        turnId: String,
        message: String,
        responses: List<Map<String, String>>,
    ) = lockFor(runId, sessionId).withLock {
        val payload = read(runId, sessionId)
        val entries = payload["entries"]?.jsonArray?.map { it.jsonObject }?.toMutableList() ?: mutableListOf()
        appendCandidates(entries, turnId, message, responses)
        writeEntries(runId, sessionId, entries)
    }

    /** Replace one turn after an explicit correction so retrieval cannot return superseded text. */
    fun replaceTurn(
        runId: String,
        sessionId: String,
        turnId: String,
        message: String,
        responses: List<Map<String, String>>,
    ) = lockFor(runId, sessionId).withLock {
        val retained = read(runId, sessionId)["entries"]?.jsonArray.orEmpty()
            .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .filterNot { it["turn_id"]?.jsonPrimitive?.contentOrNull == turnId }
            .toMutableList()
        appendCandidates(retained, turnId, message, responses)
        writeEntries(runId, sessionId, retained)
    }

    /** Copy only the completed turns retained by a newly-created dialogue branch. */
    fun copyForBranch(
        runId: String,
        sourceSessionId: String,
        targetSessionId: String,
        retainedTurnIds: Set<String>,
    ) {
        if (sourceSessionId == targetSessionId) return
        val sourcePayload = lockFor(runId, sourceSessionId).withLock { read(runId, sourceSessionId) }
        val retained = sourcePayload["entries"]?.jsonArray.orEmpty()
            .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .filter { it["turn_id"]?.jsonPrimitive?.contentOrNull in retainedTurnIds }
        if (retained.isEmpty()) return
        lockFor(runId, targetSessionId).withLock {
            write(runId, targetSessionId, buildJsonObject {
                put("version", 1)
                put("session_id", targetSessionId)
                put("entries", buildJsonArray { retained.forEach(::add) })
                put("updated_at", nowIsoString())
            })
        }
    }

    fun search(
        runId: String,
        sessionId: String,
        query: String,
        limit: Int = 3,
        currentTurnId: String = "",
    ): List<Map<String, Any?>> = lockFor(runId, sessionId).withLock {
        val queryText = normalize(query)
        if (queryText.isBlank()) return@withLock emptyList()
        val queryTokens = tokens(queryText)
        val entries = read(runId, sessionId)["entries"]?.jsonArray.orEmpty()
            .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        val selected = entries
            .mapNotNull { raw ->
                val entry = raw
                if (entry["enabled"]?.jsonPrimitive?.contentOrNull == "false") return@mapNotNull null
                if (entry["status"]?.jsonPrimitive?.contentOrNull in setOf("stale", "conflict")) return@mapNotNull null
                val text = entry["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (text.isBlank()) return@mapNotNull null
                val overlap = queryTokens.count { text.lowercase().contains(it) }
                var score = overlap.toDouble()
                if (text.lowercase().contains(queryText.lowercase())) score += 5.0
                if (score <= 0.0) return@mapNotNull null
                score to entry
            }
            .sortedByDescending { it.first }
            .take(limit.coerceIn(1, 6))
        if (currentTurnId.isNotBlank() && selected.isNotEmpty()) {
            val selectedIds = selected.mapTo(hashSetOf()) { it.second["memory_id"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            val timestamp = nowIsoString()
            val updated = entries.map { entry ->
                if (entry["memory_id"]?.jsonPrimitive?.contentOrNull !in selectedIds) return@map entry
                buildJsonObject {
                    entry.forEach { (key, value) -> put(key, value) }
                    put("last_hit_turn_id", currentTurnId)
                    val hitCount = entry["hit_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    put("hit_count", hitCount + 1)
                    put("last_hit_at", timestamp)
                }
            }
            writeEntries(runId, sessionId, updated)
        }
        selected
            .map { (score, entry) ->
                mapOf<String, Any?>(
                    "text" to trim(entry["text"]?.jsonPrimitive?.contentOrNull.orEmpty(), 180),
                    "score" to ((score * 1000).toInt() / 1000.0),
                    "speaker" to entry["speaker"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    "turn_id" to entry["turn_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    "kind" to "dialogue",
                )
            }
    }

    fun qualityReport(runId: String, sessionId: String): MemoryQualityReportDto =
        lockFor(runId, sessionId).withLock {
            val automatic = read(runId, sessionId)["entries"]?.jsonArray.orEmpty()
                .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                .map(::toAutomaticMemory)
            val controlled = runCatching { storage.getDialogueSession(runId, sessionId) }.getOrNull()
                ?.get("memory_ledger")?.jsonArray.orEmpty()
                .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                .map(::toControlledMemory)
            val groups = automatic
                .filter { it.status == "active" && it.text.isNotBlank() }
                .groupBy { duplicateKey(it.text) }
                .values
                .filter { it.size > 1 }
                .map { group -> group.map(DialogueMemoryDto::memoryId) }
            val latestHitTurnId = automatic.filter { it.lastHitAt.isNotBlank() }
                .maxByOrNull(DialogueMemoryDto::lastHitAt)?.lastHitTurnId.orEmpty()
            val decoratedAutomatic = automatic.map { memory ->
                val duplicateOf = groups.firstOrNull { memory.memoryId in it }
                    ?.firstOrNull { it != memory.memoryId }.orEmpty()
                memory.copy(duplicateOf = duplicateOf)
            }
            val allEntries = controlled + decoratedAutomatic
            val visibleAutomatic = (
                decoratedAutomatic.filter {
                    it.status != "active" || it.lastHitTurnId == latestHitTurnId || it.duplicateOf.isNotBlank()
                } + decoratedAutomatic.takeLast(80)
            ).distinctBy(DialogueMemoryDto::memoryId).takeLast(120)
            MemoryQualityReportDto(
                entries = controlled + visibleAutomatic,
                latestHitTurnId = latestHitTurnId,
                duplicateGroups = groups,
                activeCount = allEntries.count { it.enabled && it.status == "active" },
                staleCount = allEntries.count { it.status == "stale" },
                conflictCount = allEntries.count { it.status == "conflict" },
            )
        }

    fun updateStatus(runId: String, sessionId: String, memoryId: String, status: String): MemoryQualityReportDto =
        lockFor(runId, sessionId).withLock {
            require(status in setOf("active", "stale", "conflict")) { "无效的记忆状态" }
            val entries = read(runId, sessionId)["entries"]?.jsonArray.orEmpty()
                .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            require(entries.any { it["memory_id"]?.jsonPrimitive?.contentOrNull == memoryId }) { "自动记忆不存在" }
            val updated = entries.map { entry ->
                if (entry["memory_id"]?.jsonPrimitive?.contentOrNull != memoryId) return@map entry
                buildJsonObject {
                    entry.forEach { (key, value) -> put(key, value) }
                    put("status", status)
                    put("updated_at", nowIsoString())
                }
            }
            writeEntries(runId, sessionId, updated)
            qualityReportUnlocked(runId, sessionId)
        }

    fun mergeDuplicates(runId: String, sessionId: String): MemoryQualityReportDto =
        lockFor(runId, sessionId).withLock {
            val entries = read(runId, sessionId)["entries"]?.jsonArray.orEmpty()
                .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            val duplicateGroups = entries.groupBy { duplicateKey(it["text"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
                .values.filter { it.size > 1 }
            if (duplicateGroups.isNotEmpty()) {
                val removedIds = duplicateGroups.flatMap { group -> group.drop(1) }
                    .mapTo(hashSetOf()) { it["memory_id"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                val replacements = duplicateGroups.associate { group ->
                    val retained = group.first()
                    val retainedId = retained["memory_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    retainedId to buildJsonObject {
                        retained.forEach { (key, value) -> put(key, value) }
                        put("text", group.maxBy { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty().length }["text"]!!)
                        put("merged_source_ids", buildJsonArray {
                            group.drop(1).forEach {
                                add(JsonPrimitive(it["memory_id"]?.jsonPrimitive?.contentOrNull.orEmpty()))
                            }
                        })
                        put("updated_at", nowIsoString())
                    }
                }
                writeEntries(runId, sessionId, entries.mapNotNull { entry ->
                    val id = entry["memory_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    when {
                        id in removedIds -> null
                        id in replacements -> replacements.getValue(id)
                        else -> entry
                    }
                })
            }
            qualityReportUnlocked(runId, sessionId)
        }

    private fun qualityReportUnlocked(runId: String, sessionId: String): MemoryQualityReportDto {
        val automatic = read(runId, sessionId)["entries"]?.jsonArray.orEmpty()
            .mapNotNull { runCatching { it.jsonObject }.getOrNull() }.map(::toAutomaticMemory)
        val controlled = runCatching { storage.getDialogueSession(runId, sessionId) }.getOrNull()
            ?.get("memory_ledger")?.jsonArray.orEmpty()
            .mapNotNull { runCatching { it.jsonObject }.getOrNull() }.map(::toControlledMemory)
        val groups = automatic.filter { it.status == "active" }.groupBy { duplicateKey(it.text) }
            .values.filter { it.size > 1 }.map { it.map(DialogueMemoryDto::memoryId) }
        val latestHitTurnId = automatic.filter { it.lastHitAt.isNotBlank() }
            .maxByOrNull(DialogueMemoryDto::lastHitAt)?.lastHitTurnId.orEmpty()
        val decoratedAutomatic = automatic.map { memory ->
            memory.copy(duplicateOf = groups.firstOrNull { memory.memoryId in it }
                ?.firstOrNull { it != memory.memoryId }.orEmpty())
        }
        val allEntries = controlled + decoratedAutomatic
        val visibleAutomatic = (
            decoratedAutomatic.filter {
                it.status != "active" || it.lastHitTurnId == latestHitTurnId || it.duplicateOf.isNotBlank()
            } + decoratedAutomatic.takeLast(80)
        ).distinctBy(DialogueMemoryDto::memoryId).takeLast(120)
        return MemoryQualityReportDto(
            entries = controlled + visibleAutomatic,
            latestHitTurnId = latestHitTurnId,
            duplicateGroups = groups,
            activeCount = allEntries.count { it.enabled && it.status == "active" },
            staleCount = allEntries.count { it.status == "stale" },
            conflictCount = allEntries.count { it.status == "conflict" },
        )
    }

    private fun toAutomaticMemory(entry: JsonObject) = DialogueMemoryDto(
        memoryId = entry["memory_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        text = entry["text"]?.jsonPrimitive?.contentOrNull.orEmpty().take(500),
        category = "long_term",
        pinned = entry["pinned"]?.jsonPrimitive?.contentOrNull == "true",
        enabled = entry["enabled"]?.jsonPrimitive?.contentOrNull != "false",
        createdAt = entry["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        updatedAt = entry["updated_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        source = "automatic",
        sourceTurnId = entry["turn_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        status = entry["status"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "active" },
        lastHitTurnId = entry["last_hit_turn_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        lastHitAt = entry["last_hit_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        hitCount = entry["hit_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
        mergedSourceIds = entry["merged_source_ids"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull },
    )

    private fun toControlledMemory(entry: JsonObject) = DialogueMemoryDto(
        memoryId = entry["memory_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        text = entry["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        category = entry["category"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "story" },
        pinned = entry["pinned"]?.jsonPrimitive?.contentOrNull == "true",
        enabled = entry["enabled"]?.jsonPrimitive?.contentOrNull != "false",
        createdAt = entry["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        updatedAt = entry["updated_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        source = "user",
    )

    private fun duplicateKey(text: String): String = normalize(text).lowercase()
        .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")

    private fun read(runId: String, sessionId: String): JsonObject {
        val file = file(runId, sessionId)
        if (!storage.isFile(file)) return emptyPayload(sessionId)
        return runCatching { json.parseToJsonElement(storage.readText(file)).jsonObject }
            .getOrElse { emptyPayload(sessionId) }
    }

    private fun write(runId: String, sessionId: String, payload: JsonObject) {
        storage.writeTextAtomically(file(runId, sessionId), json.encodeToString(JsonObject.serializer(), payload))
    }

    private fun appendCandidates(
        entries: MutableList<JsonObject>,
        turnId: String,
        message: String,
        responses: List<Map<String, String>>,
    ) {
        val timestamp = nowIsoString()
        val candidates = buildList {
            if (message.isNotBlank()) add("User" to message.trim())
            responses.forEach { response ->
                val speaker = response["speaker"].orEmpty().trim()
                val text = response["message"].orEmpty().trim()
                if (speaker.isNotBlank() && text.isNotBlank()) add(speaker to text)
            }
        }
        candidates.forEachIndexed { index, (speaker, text) ->
            val memoryId = "mem-${turnId.ifBlank { "unknown" }}-$index"
            if (entries.any { it["memory_id"]?.jsonPrimitive?.contentOrNull == memoryId }) {
                return@forEachIndexed
            }
            entries += buildJsonObject {
                put("memory_id", memoryId)
                put("turn_id", turnId)
                put("speaker", speaker)
                put("text", normalize(text).take(MAX_TEXT_LENGTH))
                put("created_at", timestamp)
                put("updated_at", timestamp)
                put("source", "automatic")
                put("enabled", true)
                put("pinned", false)
                put("status", "active")
                put("hit_count", 0)
            }
        }
    }

    private fun writeEntries(runId: String, sessionId: String, entries: List<JsonObject>) {
        val timestamp = nowIsoString()
        write(runId, sessionId, buildJsonObject {
            put("version", 1)
            put("session_id", sessionId)
            put("entries", buildJsonArray { entries.takeLast(MAX_ENTRIES).forEach(::add) })
            put("updated_at", timestamp)
        })
    }

    private fun file(runId: String, sessionId: String) =
        storage.getDialogueSessionManifestFile(runId, sessionId).parent!! / "long_term_memory.json"

    private fun emptyPayload(sessionId: String) = buildJsonObject {
        put("version", 1)
        put("session_id", sessionId)
        put("entries", JsonArray(emptyList()))
        put("updated_at", "")
    }

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun trim(value: String, max: Int): String =
        if (value.length <= max) value else value.take(max - 1).trimEnd() + "…"

    private fun tokens(value: String): Set<String> {
        val result = linkedSetOf<String>()
        Regex("[A-Za-z0-9_]{2,}|[\\u3400-\\u9fff]+|[^\\s]{2,}").findAll(value.lowercase()).forEach { match ->
            val token = match.value
            result += token
            if (token.all { it in '\u3400'..'\u9fff' }) {
                for (index in 0 until token.length - 1) result += token.substring(index, index + 2)
            }
        }
        return result
    }

    companion object {
        private val locks = HashMap<String, SimpleLock>()
        private val locksLock = SimpleLock()
        private const val MAX_ENTRIES = 400
        private const val MAX_TEXT_LENGTH = 1200
    }
}

package top.wkbin.zaomeng.ktor.services

import java.io.File
import java.time.Instant
import java.util.UUID
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
import top.wkbin.zaomeng.data.api.DeleteStatusDto
import top.wkbin.zaomeng.data.api.SaveWorldFactRequest
import top.wkbin.zaomeng.data.api.WorldFactDto
import top.wkbin.zaomeng.data.api.WorldMemoryDto
import java.util.concurrent.ConcurrentHashMap

class WorldMemoryService(private val storage: StorageService) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }
    private val runLocks = ConcurrentHashMap<String, Any>()

    private fun lockFor(runId: String): Any = runLocks.computeIfAbsent(runId) { Any() }

    private val eventCategory = mapOf(
        "scene_transition" to "location",
        "cast_enter" to "location",
        "cast_exit" to "location",
        "relationship_shift" to "relationship",
        "time_change" to "setting",
        "environment_change" to "setting",
    )

    fun get(runId: String): WorldMemoryDto = decode(read(runId))

    fun saveFact(runId: String, factId: String, request: SaveWorldFactRequest): WorldFactDto {
        require(request.summary.isNotBlank()) { "Fact summary must not be empty." }
        require(request.category in CATEGORIES) { "Unsupported world fact category." }
        if (factId.isNotBlank()) PathSafety.validateStorageId(factId, "fact_id")
        val payload = read(runId)
        val facts = payload["facts"]?.jsonArray?.toMutableList() ?: mutableListOf()
        val existingIndex = if (factId.isBlank()) -1 else facts.indexOfFirst {
            it.jsonObject["fact_id"]?.jsonPrimitive?.contentOrNull == factId
        }
        if (factId.isNotBlank() && existingIndex < 0) throw NoSuchElementException("Fact not found: $factId")
        val existing = if (existingIndex >= 0) facts[existingIndex].jsonObject else JsonObject(emptyMap())
        val resolvedId = factId.ifBlank { "fact-${UUID.randomUUID().toString().replace("-", "").take(12)}" }
        val now = Instant.now().toString()
        val updated = buildJsonObject {
            existing.forEach(::put)
            put("fact_id", resolvedId)
            put("category", request.category)
            put("summary", request.summary.trim().take(500))
            put("characters", buildJsonArray {
                request.characters.map(String::trim).filter(String::isNotBlank).distinct().take(20)
                    .forEach { add(JsonPrimitive(it)) }
            })
            put("location", request.location.trim().take(100))
            put("time_hint", request.timeHint.trim().take(80))
            put("source", existing["source"] ?: JsonPrimitive("manual"))
            put("locked", request.locked)
            put("active", request.active)
            put("created_at", existing["created_at"] ?: JsonPrimitive(now))
            put("updated_at", now)
        }
        if (existingIndex < 0) facts.add(updated) else facts[existingIndex] = updated
        write(runId, buildJsonObject {
            payload.forEach(::put)
            put("facts", buildJsonArray { facts.takeLast(MAX_ITEMS).forEach(::add) })
            put("updated_at", now)
        })
        return json.decodeFromJsonElement(WorldFactDto.serializer(), updated)
    }

    fun deleteFact(runId: String, factId: String): DeleteStatusDto {
        PathSafety.validateStorageId(factId, "fact_id")
        val payload = read(runId)
        val facts = payload["facts"]?.jsonArray ?: JsonArray(emptyList())
        val remaining = facts.filterNot { it.jsonObject["fact_id"]?.jsonPrimitive?.contentOrNull == factId }
        if (remaining.size == facts.size) throw NoSuchElementException("Fact not found: $factId")
        write(runId, buildJsonObject {
            payload.forEach(::put)
            put("facts", buildJsonArray { remaining.forEach(::add) })
            put("updated_at", Instant.now().toString())
        })
        return DeleteStatusDto(status = "deleted")
    }

    /**
     * 对话轮次提交后同步世界记忆（迁移自 Python WorldMemoryStore.sync_completed_turn）。
     * 把本轮的剧情事件写成带 source_session_id/source_turn_id 的事实，并为每一轮追加一条
     * 时间线条目（按 turn_key 幂等去重）。
     */
    fun syncCompletedTurn(
        runId: String,
        sessionId: String,
        turnId: String,
        title: String,
        participants: List<String>,
        events: List<JsonObject>,
        location: String,
        timeHint: String,
        consistencyStatus: String,
        knowledgeLedger: List<JsonObject>,
        updatedAt: String,
    ): JsonObject {
        val safeSessionId = PathSafety.validateStorageId(sessionId, "session_id")
        val safeTurnId = PathSafety.validateStorageId(turnId, "turn_id")
        val now = updatedAt.ifBlank { Instant.now().toString() }
        val turnKey = "$safeSessionId:$safeTurnId"
        val cleanParticipants = participants.map(String::trim).filter(String::isNotBlank).distinct()
        val cleanLocation = location.trim().take(100)
        val cleanTimeHint = timeHint.trim().take(80)

        synchronized(lockFor(runId)) {
            val payload = read(runId)
            val facts = (payload["facts"]?.jsonArray ?: JsonArray(emptyList())).toMutableList()
            val bySourceKey = LinkedHashMap<String, Int>()
            facts.forEachIndexed { index, item ->
                runCatching {
                    item.jsonObject["source_key"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                        ?.let { bySourceKey[it] = index }
                }
            }

            events.forEachIndexed { index, rawEvent ->
                val cue = rawEvent["cue"]?.jsonPrimitive?.contentOrNull?.trim()?.take(500).orEmpty()
                if (cue.isEmpty()) return@forEachIndexed
                val sourceKey = "$turnKey:event:$index"
                val actor = rawEvent["actor"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val target = rawEvent["target"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val characters = listOf(actor, target).filter(String::isNotBlank).distinct()
                val kind = rawEvent["kind"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val eventLocation = rawEvent["location_hint"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf(String::isNotBlank) ?: cleanLocation
                val eventTime = rawEvent["time_hint"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf(String::isNotBlank) ?: cleanTimeHint
                val item = buildJsonObject {
                    put("fact_id", "fact-" + UUID.randomUUID().toString().replace("-", "").take(12))
                    put("category", eventCategory[kind] ?: "event")
                    put("summary", cue)
                    put("characters", buildJsonArray { characters.forEach { add(JsonPrimitive(it)) } })
                    put("location", eventLocation)
                    put("time_hint", eventTime)
                    put("source_session_id", safeSessionId)
                    put("source_turn_id", safeTurnId)
                    put("source_key", sourceKey)
                    put("source", "dialogue")
                    put("locked", false)
                    put("active", true)
                    put("created_at", now)
                    put("updated_at", now)
                }
                val existingIndex = bySourceKey[sourceKey]
                if (existingIndex == null) {
                    bySourceKey[sourceKey] = facts.size
                    facts.add(item)
                } else {
                    val existing = facts[existingIndex].jsonObject
                    val locked = existing["locked"]?.jsonPrimitive?.contentOrNull == "true" ||
                        existing["locked"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
                    if (!locked) {
                        val updated = buildJsonObject {
                            item.forEach { (k, v) -> if (k != "fact_id" && k != "created_at") put(k, v) }
                            put("fact_id", existing["fact_id"] ?: item["fact_id"] ?: JsonPrimitive(""))
                            put("created_at", existing["created_at"] ?: item["created_at"] ?: JsonPrimitive(now))
                        }
                        facts[existingIndex] = updated
                    }
                }
            }

            knowledgeLedger.forEach { rawSecret ->
                val summary = (rawSecret["secret"] ?: rawSecret["summary"])?.jsonPrimitive?.contentOrNull
                    ?.trim()?.take(500).orEmpty()
                if (summary.isEmpty()) return@forEach
                val sourceKey = "knowledge:${summary.lowercase()}"
                if (sourceKey in bySourceKey) return@forEach
                val knowers = (rawSecret["knowers"]?.jsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                    .map(String::trim).filter(String::isNotBlank).distinct()
                facts.add(buildJsonObject {
                    put("fact_id", "fact-" + UUID.randomUUID().toString().replace("-", "").take(12))
                    put("category", "secret")
                    put("summary", summary)
                    put("characters", buildJsonArray { knowers.forEach { add(JsonPrimitive(it)) } })
                    put("location", "")
                    put("time_hint", "")
                    put("source_session_id", safeSessionId)
                    put("source_turn_id", safeTurnId)
                    put("source_key", sourceKey)
                    put("source", "dialogue")
                    put("locked", false)
                    put("active", true)
                    put("created_at", now)
                    put("updated_at", now)
                })
                bySourceKey[sourceKey] = facts.size - 1
            }

            val timeline = ((payload["timeline"]?.jsonArray ?: JsonArray(emptyList())))
                .filterNot { item ->
                    runCatching { item.jsonObject["turn_key"]?.jsonPrimitive?.contentOrNull == turnKey }
                        .getOrDefault(false)
                }
                .toMutableList()
            timeline.add(buildJsonObject {
                put("timeline_id", "timeline-" + UUID.randomUUID().toString().replace("-", "").take(12))
                put("turn_key", turnKey)
                put("source_session_id", safeSessionId)
                put("source_turn_id", safeTurnId)
                put("title", title.trim().take(160).ifEmpty { "剧情推进" })
                put("participants", buildJsonArray { cleanParticipants.forEach { add(JsonPrimitive(it)) } })
                put("event_types", buildJsonArray {
                    val kinds = events.mapNotNull { it["kind"]?.jsonPrimitive?.contentOrNull?.trim() }
                        .filter(String::isNotBlank).distinct()
                    (kinds.ifEmpty { listOf("dialogue") }).forEach { add(JsonPrimitive(it)) }
                })
                put("location", cleanLocation)
                put("time_hint", cleanTimeHint)
                put("consistency_status", consistencyStatus.trim().ifEmpty { "pass" })
                put("updated_at", now)
            })
            val trimmedFacts = facts.takeLast(MAX_ITEMS)
            val trimmedTimeline = timeline.sortedBy {
                it.jsonObject["updated_at"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }.takeLast(MAX_ITEMS)
            val updated = buildJsonObject {
                payload.forEach { (k, v) -> if (k !in setOf("facts", "timeline", "updated_at")) put(k, v) }
                put("facts", buildJsonArray { trimmedFacts.forEach(::add) })
                put("timeline", buildJsonArray { trimmedTimeline.forEach(::add) })
                put("updated_at", now)
            }
            write(runId, updated)
            return updated
        }
    }

    /**
     * 删除会话时清理该会话归属的世界记忆（facts/timeline 中 source_session_id 匹配的条目）。
     */
    fun purgeSession(runId: String, sessionId: String) {
        val safeSessionId = PathSafety.validateStorageId(sessionId, "session_id")
        synchronized(lockFor(runId)) {
            val payload = read(runId)
            val facts = payload["facts"]?.jsonArray ?: JsonArray(emptyList())
            val remainingFacts = facts.filterNot { item ->
                runCatching { item.jsonObject["source_session_id"]?.jsonPrimitive?.contentOrNull == safeSessionId }
                    .getOrDefault(false)
            }
            val timeline = payload["timeline"]?.jsonArray ?: JsonArray(emptyList())
            val remainingTimeline = timeline.filterNot { item ->
                runCatching { item.jsonObject["source_session_id"]?.jsonPrimitive?.contentOrNull == safeSessionId }
                    .getOrDefault(false)
            }
            if (remainingFacts.size == facts.size && remainingTimeline.size == timeline.size) return
            write(runId, buildJsonObject {
                payload.forEach { (k, v) -> if (k !in setOf("facts", "timeline", "updated_at")) put(k, v) }
                put("facts", buildJsonArray { remainingFacts.forEach(::add) })
                put("timeline", buildJsonArray { remainingTimeline.forEach(::add) })
                put("updated_at", Instant.now().toString())
            })
        }
    }

    private fun read(runId: String): JsonObject {
        if (!storage.runExists(runId)) throw NoSuchElementException("Run not found: $runId")
        val file = file(runId)
        if (!file.isFile) return emptyPayload()
        return json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun write(runId: String, payload: JsonObject) {
        storage.writeTextAtomically(file(runId), json.encodeToString(JsonObject.serializer(), payload))
    }

    private fun decode(payload: JsonObject): WorldMemoryDto =
        json.decodeFromJsonElement(WorldMemoryDto.serializer(), payload)

    private fun file(runId: String): File = File(storage.getRunDirectory(runId), "world_memory.json")

    private fun emptyPayload() = buildJsonObject {
        put("version", 1)
        put("facts", JsonArray(emptyList()))
        put("timeline", JsonArray(emptyList()))
        put("updated_at", "")
    }

    companion object {
        private const val MAX_ITEMS = 500
        private val CATEGORIES = setOf("event", "location", "possession", "status", "commitment", "secret", "relationship", "setting")
    }
}

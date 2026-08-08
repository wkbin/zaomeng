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

class WorldMemoryService(private val storage: StorageService) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

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

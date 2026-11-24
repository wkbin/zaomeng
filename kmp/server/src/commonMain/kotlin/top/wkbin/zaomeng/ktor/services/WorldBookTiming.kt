package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.WorldBookMatchDto

/** Computes timer updates for one prompt. Only a committed reply publishes them. */
internal class WorldBookTiming(session: JsonObject, private val messageCount: Long) {
    private val initial = stateOf(session)
    private val current = initial.toMutableMap()
    private val visited = mutableSetOf<String>()
    private val placements = mutableMapOf<WorldBookMatchDto, Entry>()

    data class Entry(val key: String, val sticky: Int, val cooldown: Int)

    enum class Phase { Sticky, Cooldown }

    private data class Timer(val start: Long, val end: Long, val sticky: Boolean, val protected: Boolean = false) {
        fun encode() = buildJsonObject {
            put("start", start)
            put("end", end)
            if (sticky) put("sticky", true)
            if (protected) put("protected", true)
        }
    }

    fun phase(entry: Entry): Phase? {
        visited.add(entry.key)
        val timer = decode(current[entry.key] as? JsonObject) ?: return null
        if (messageCount < timer.start || (!timer.protected && messageCount == timer.start)) {
            current.remove(entry.key)
            return null
        }
        if (messageCount < timer.end) return if (timer.sticky) Phase.Sticky else Phase.Cooldown
        if (timer.sticky && entry.cooldown > 0) {
            current[entry.key] = Timer(messageCount, messageCount + entry.cooldown, sticky = false, protected = true).encode()
            return Phase.Cooldown
        }
        current.remove(entry.key)
        return null
    }

    fun selected(match: WorldBookMatchDto, entry: Entry) {
        placements[match] = entry
    }

    fun finish(injected: List<WorldBookMatchDto>): JsonObject? {
        current.keys.filterNot(visited::contains).forEach { key ->
            val timer = decode(current[key] as? JsonObject)
            if (timer == null || messageCount >= timer.end) current.remove(key)
        }
        injected.mapNotNull(placements::get).forEach { entry ->
            if (entry.key !in current) {
                val sticky = entry.sticky > 0
                val duration = if (sticky) entry.sticky else entry.cooldown
                current[entry.key] = Timer(messageCount, messageCount + duration, sticky).encode()
            }
        }
        return buildJsonObject {
            for ((key, timer) in current) {
                if (timer != initial[key]) put(key, timer)
            }
            for (key in initial.keys - current.keys) put(key, JsonNull)
        }.takeIf(JsonObject::isNotEmpty)
    }

    companion object {
        const val FIELD = "world_book_timing"
        const val DELTA = "world_book_timing_delta"

        private fun decode(value: JsonObject?): Timer? {
            val start = (value?.get("start") as? JsonPrimitive)?.longOrNull ?: return null
            val end = (value["end"] as? JsonPrimitive)?.longOrNull ?: return null
            val sticky = (value["sticky"] as? JsonPrimitive)?.booleanOrNull == true
            val protected = (value["protected"] as? JsonPrimitive)?.booleanOrNull == true
            return Timer(start, end, sticky, protected)
        }

        private fun stateOf(session: JsonObject): JsonObject =
            session[FIELD] as? JsonObject ?: JsonObject(emptyMap())

        fun apply(session: JsonObject, delta: JsonObject): JsonObject =
            JsonObject(stateOf(session).toMutableMap().apply { applyDelta(delta) })

        fun fromTranscript(transcript: List<JsonObject>): JsonObject {
            val state = mutableMapOf<String, JsonElement>()
            for (message in transcript) {
                (message[DELTA] as? JsonObject)?.let { state.applyDelta(it) }
            }
            return JsonObject(state)
        }

        private fun MutableMap<String, JsonElement>.applyDelta(delta: JsonObject) {
            for ((key, value) in delta) {
                if (value == JsonNull) remove(key) else put(key, value)
            }
        }
    }
}

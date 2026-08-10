package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SceneProgressStateTest {
    @Test
    fun `derived state keeps persisted primitives as plain values across turns`() {
        val firstSession = buildJsonObject {
            put("participants", JsonArray(listOf(JsonPrimitive("Alice"))))
            put("state", buildJsonObject {
                put("scene", buildJsonObject {
                    put("location", "garden")
                    put("time_hint", "evening")
                    put("atmosphere_summary", "quiet")
                    put("progression_note", "opening")
                    put("updated_at", "2026-08-10T10:00:00Z")
                })
                put("presence", buildJsonObject {
                    put("present_participants", JsonArray(listOf(JsonPrimitive("Alice"))))
                    put("offstage_participants", JsonArray(emptyList()))
                    put("updated_at", "2026-08-10T10:00:00Z")
                })
                put("progression", buildJsonObject {
                    put("should_offer_scene_shift", false)
                    put("turns_in_current_scene", 1)
                    put("beat_maturity", 10)
                    put("updated_at", "2026-08-10T10:00:00Z")
                })
                put("signals", buildJsonObject {
                    put("recent", JsonArray(emptyList()))
                    put("by_type", buildJsonObject {})
                    put("updated_at", "2026-08-10T10:00:00Z")
                })
            })
        }

        val transcript = listOf(
            mapOf<String, Any?>(
                "speaker" to "Alice",
                "role" to "assistant",
                "message" to "Still here.",
                "timestamp" to "2026-08-10T10:00:01Z",
            ),
        )
        val first = SceneProgressState.deriveSceneProgressState(
            session = firstSession,
            transcript = transcript,
            updatedAt = "2026-08-10T10:00:02Z",
        )
        val secondSession = buildJsonObject {
            firstSession.forEach { (key, value) -> put(key, value) }
            put("state", SceneProgressState.stateToJsonObject(first))
        }
        val second = SceneProgressState.deriveSceneProgressState(
            session = secondSession,
            transcript = transcript,
            updatedAt = "2026-08-10T10:00:03Z",
        )
        val persisted = SceneProgressState.stateToJsonObject(second)

        val scene = persisted["scene"]!!.jsonObject
        assertEquals("evening", scene["time_hint"]?.jsonPrimitive?.contentOrNull)
        assertFalse(scene["time_hint"].toString().contains("\\\"evening\\\""))
        assertTrue(scene["progression_note"]!!.jsonPrimitive.content.length < 256)
        val signals = persisted["signals"]!!.jsonObject
        assertEquals("2026-08-10T10:00:00Z", signals["updated_at"]?.jsonPrimitive?.contentOrNull)
    }
}

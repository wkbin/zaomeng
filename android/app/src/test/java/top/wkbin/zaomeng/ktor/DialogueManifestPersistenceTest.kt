package top.wkbin.zaomeng.ktor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class DialogueManifestPersistenceTest {
    @Test
    fun `manifest updates preserve extension fields`() {
        val original = buildJsonObject {
            put("session_id", "s1")
            put("turns", "legacy")
            put("turn_count", 0)
            put("scene_card_id", "scene-1")
        }
        val updated = buildJsonObject {
            original.forEach { (key, value) -> put(key, value) }
            put("turn_count", 2)
            put("updated_at", "now")
        }
        val decoded = Json.parseToJsonElement(updated.toString()).jsonObject
        assertEquals("scene-1", decoded["scene_card_id"]?.toString()?.trim('"'))
        assertEquals(2, decoded["turn_count"]?.toString()?.toInt())
    }
}

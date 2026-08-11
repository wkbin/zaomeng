package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class DistillResumeStateTest {
    @Test
    fun `resume preserves the complete character plan and completed progress`() {
        val manifest = buildJsonObject {
            put("run_id", JsonPrimitive("run-1"))
            put("status", JsonPrimitive("failed"))
            put("locked_characters", buildJsonArray {
                listOf("A", "B", "C", "D").forEach { add(JsonPrimitive(it)) }
            })
            put("progress", buildJsonObject {
                put("completed_characters", buildJsonArray {
                    listOf("A", "B").forEach { add(JsonPrimitive(it)) }
                })
                put("completed_count", JsonPrimitive(2))
                put("total_characters", JsonPrimitive(4))
            })
        }

        val resumed = buildResumedDistillManifest(
            manifest = manifest,
            lockedCharacters = listOf("A", "B", "C", "D"),
            completedCharacters = setOf("A", "B"),
            now = "2026-08-11T18:00:00Z",
        )

        assertEquals("running", resumed["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            listOf("A", "B", "C", "D"),
            resumed["locked_characters"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull },
        )
        val progress = resumed["progress"]?.jsonObject ?: error("missing progress")
        assertEquals(listOf("A", "B"), progress["completed_characters"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull })
        assertEquals(2, progress["completed_count"]?.jsonPrimitive?.int)
        assertEquals(4, progress["total_characters"]?.jsonPrimitive?.int)
    }

    @Test
    fun `resume drops completed names that are no longer in the locked plan`() {
        val resumed = buildResumedDistillManifest(
            manifest = buildJsonObject { put("progress", buildJsonObject {}) },
            lockedCharacters = listOf("B", "C"),
            completedCharacters = setOf("A", "B"),
            now = "2026-08-11T18:00:00Z",
        )

        val completed = resumed["progress"]?.jsonObject
            ?.get("completed_characters")?.jsonArray
            ?.map { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
        assertEquals(listOf("B"), completed)
    }
}

package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DistillChunkPlannerTest {
    @Test
    fun characterChunksPreserveStageAndFocusMetadata() {
        val payload = DistillPayload(
            prompt = "",
            references = emptyMap(),
            request = mapOf(
                "excerpt" to "甲。乙。",
                "excerpt_stages" to mapOf("start" to "甲。乙。", "mid" to "", "end" to ""),
                "excerpt_focus" to mapOf("strategy" to "original"),
            ),
            meta = emptyMap(),
        )

        val chunks = DistillChunkPlanner.buildCharacter(payload, maxChars = 20, maxSentences = 1)

        assertEquals(2, chunks.size)
        assertEquals(listOf("前段-1", "前段-2"), chunks.map { it.label })
        chunks.forEachIndexed { index, entry ->
            assertEquals("start", entry.payload.meta["chunk_stage"])
            assertEquals(index + 1, entry.payload.meta["chunk_index"])
            val focus = entry.payload.request["excerpt_focus"] as Map<*, *>
            assertEquals("chunked_character_windows", focus["strategy"])
        }
    }

    @Test
    fun relationChunksUseFallbackLabelsWithoutStageWindows() {
        val payload = DistillPayload(
            prompt = "",
            references = emptyMap(),
            request = mapOf("excerpt" to "甲乙丙丁", "excerpt_stages" to emptyMap<String, String>()),
            meta = emptyMap(),
        )

        val chunks = DistillChunkPlanner.buildRelations(payload, maxChars = 2, maxSentences = 10)

        assertEquals(listOf("关系块-1", "关系块-2"), chunks.map { it.label })
    }

    @Test
    fun thresholdPolicyChecksBothCharactersAndSentences() {
        assertFalse(DistillChunkPlanner.shouldUse("甲。", triggerChars = 10, triggerSentences = 2))
        assertTrue(DistillChunkPlanner.shouldUse("甲。乙。丙。", triggerChars = 100, triggerSentences = 2))
        assertTrue(DistillChunkPlanner.shouldUse("甲乙丙丁", triggerChars = 3, triggerSentences = 100))
    }
}

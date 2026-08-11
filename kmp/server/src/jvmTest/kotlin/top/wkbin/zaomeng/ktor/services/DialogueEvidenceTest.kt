package top.wkbin.zaomeng.ktor.services

import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DialogueEvidenceTest {
    @Test
    fun `extracts bounded source evidence actually injected into the turn`() {
        val dir = createTempDirectory("zaomeng-dialogue-evidence")
        try {
            val service = DialogueService(StorageService(dir.toString().toPath()))
            val entries = (1..5).map { index ->
                mapOf<String, Any?>(
                    "source_id" to "src-0000$index",
                    "title" to "片段 $index",
                    "excerpt" to "原文内容 $index",
                    "score" to index.toDouble(),
                    "visibility" to "public",
                    "location" to mapOf("start_char" to index * 10, "end_char" to index * 10 + 5),
                )
            }
            val payload = mapOf<String, Any?>(
                "original_source_context" to mapOf("entries" to entries),
            )

            val evidence = service.extractOriginalEvidence(payload)

            assertEquals(3, evidence.size)
            assertEquals("src-00001", evidence.first().sourceId)
            assertEquals(10, evidence.first().location.startChar)
            assertEquals("原文内容 1", evidence.first().excerpt)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `message pagination preserves evidence metadata`() {
        val item = buildJsonObject {
            put("speaker", "甲")
            put("message", "回答")
            put("turn_id", "turn-1")
            put("evidence", buildJsonArray {
                add(buildJsonObject {
                    put("source_id", "src-1")
                    put("title", "第一章")
                    put("excerpt", "证据原文")
                    put("location", buildJsonObject {
                        put("start_char", 12)
                        put("end_char", 20)
                    })
                })
            })
        }

        val dto = toTranscriptItemDto(item)

        assertEquals("src-1", dto.evidence.single().sourceId)
        assertEquals(12, dto.evidence.single().location.startChar)
    }
}

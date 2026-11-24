package top.wkbin.zaomeng.ktor.services

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath

class SessionProjectionArchivedSummaryTest {
    @Test
    fun `archived summary keeps the newest context when truncated`() {
        withProjection { storage ->
            val service = SessionProjectionService(storage)
            val summaries = assertNotNull(service.freshPromptSummaries(RUN_ID, SESSION_ID))
            val archived = summaries.second

            val summary = archived["summary"] as String
            assertTrue(summary.contains("VOL6"), "应保留较新的归档摘要")
            assertFalse(summary.contains("VOL1"), "超长时应丢弃最老的归档摘要")

            val keyPoints = (archived["key_points"] as List<*>).map { it.toString() }
            assertTrue(keyPoints.contains("point-6-c"), "应保留最新的关键点")
            assertFalse(keyPoints.contains("point-1-a"), "关键点超出数量时应丢弃最老的")
        }
    }

    private fun withProjection(block: (StorageService) -> Unit) {
        val dir = createTempDirectory("zaomeng-archived-summary")
        try {
            val storage = StorageService(dir.toString().toPath())
            storage.writeRunManifest(RUN_ID, buildJsonObject { put("run_id", RUN_ID) })
            storage.writeTextAtomically(
                storage.getDialogueSessionManifestFile(RUN_ID, SESSION_ID),
                buildJsonObject {
                    put("run_id", RUN_ID)
                    put("session_id", SESSION_ID)
                    put("revision", REVISION)
                    put("transcript_start", 0)
                    put("transcript", buildJsonArray {
                        repeat(ENTRY_COUNT) { index ->
                            add(buildJsonObject {
                                put("speaker", "角色")
                                put("message", "第 $index 句")
                                put("turn_id", "turn-$index")
                            })
                        }
                    })
                }.toString(),
            )
            val volumes = (1..10).map { number ->
                SummaryVolume(
                    startSeq = (number - 1) * 2,
                    endSeq = number * 2,
                    sourceRevision = REVISION,
                    sourceHash = "hash-$number",
                    summary = "VOL$number" + "x".repeat(396),
                    keyPoints = listOf("point-$number-a", "point-$number-b", "point-$number-c"),
                    turnCount = 1,
                )
            }
            SessionProjectionStore(storage).publish(
                REVISION,
                SessionProjection(
                    projectionVersion = SESSION_PROJECTION_VERSION,
                    runId = RUN_ID,
                    sessionId = SESSION_ID,
                    sourceRevision = REVISION,
                    sourceStartSeq = 0,
                    sourceEndSeq = ENTRY_COUNT,
                    volumes = volumes,
                    sessionSummary = buildJsonObject { put("recap", "最近剧情") },
                    generatedAt = "2026-11-20T00:00:00Z",
                ),
            )
            block(storage)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val SESSION_ID = "session-1"
        const val REVISION = 1L
        const val ENTRY_COUNT = 20
    }
}

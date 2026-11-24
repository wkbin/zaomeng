package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LongTermMemoryQualityTest {
    @Test
    fun `tracks source hits merges duplicates and excludes stale automatic memories`() {
        val dir = createTempDirectory("zaomeng-memory-quality")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-memory"
            val sessionId = "session-memory"
            val manifest = storage.getDialogueSessionManifestFile(runId, sessionId)
            storage.mkdirs(manifest.parent!!)
            storage.writeTextAtomically(
                manifest,
                buildJsonObject {
                    put("run_id", runId)
                    put("session_id", sessionId)
                    put("memory_ledger", buildJsonArray {
                        add(buildJsonObject {
                            put("memory_id", "user-fixed")
                            put("text", "用户固定事实")
                            put("category", "story")
                            put("pinned", true)
                            put("enabled", true)
                        })
                    })
                }.toString(),
            )
            val service = LongTermMemoryService(storage)
            service.appendTurn(runId, sessionId, "turn-1", "同一条自动事实", emptyList())
            service.appendTurn(runId, sessionId, "turn-2", "同一条自动事实", emptyList())

            val initial = service.qualityReport(runId, sessionId)
            assertEquals(1, initial.entries.count { it.source == "user" && it.pinned })
            assertEquals(1, initial.duplicateGroups.size)
            assertTrue(initial.entries.filter { it.source == "automatic" }.all { it.sourceTurnId.isNotBlank() })

            val hits = service.search(runId, sessionId, "自动事实", limit = 1, currentTurnId = "turn-3")
            assertEquals(1, hits.size)
            val hitReport = service.qualityReport(runId, sessionId)
            assertEquals("turn-3", hitReport.latestHitTurnId)
            assertEquals(1, hitReport.entries.single { it.lastHitTurnId == "turn-3" }.hitCount)

            val merged = service.mergeDuplicates(runId, sessionId)
            val automatic = merged.entries.filter { it.source == "automatic" }
            assertEquals(1, automatic.size)
            assertTrue(automatic.single().mergedSourceIds.isNotEmpty())

            val stale = service.updateStatus(runId, sessionId, automatic.single().memoryId, "stale")
            assertEquals(1, stale.staleCount)
            assertTrue(service.search(runId, sessionId, "自动事实", currentTurnId = "turn-4").isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mergeDuplicates ignores entries without text instead of crashing`() {
        val dir = createTempDirectory("zaomeng-memory-merge-empty")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-memory-empty"
            val sessionId = "session-memory-empty"
            val memoryFile = storage.getDialogueSessionManifestFile(runId, sessionId).parent!! / "long_term_memory.json"
            storage.mkdirs(memoryFile.parent!!)
            storage.writeTextAtomically(
                memoryFile,
                buildJsonObject {
                    put("run_id", runId)
                    put("session_id", sessionId)
                    put("entries", buildJsonArray {
                        add(buildJsonObject {
                            put("memory_id", "broken-1")
                            put("status", "active")
                        })
                        add(buildJsonObject {
                            put("memory_id", "broken-2")
                            put("status", "active")
                        })
                        add(buildJsonObject {
                            put("memory_id", "real-1")
                            put("text", "有效记忆")
                            put("status", "active")
                        })
                    })
                }.toString(),
            )

            val service = LongTermMemoryService(storage)
            val report = service.mergeDuplicates(runId, sessionId)

            assertTrue(report.duplicateGroups.isEmpty())
            assertEquals(1, report.entries.count { it.memoryId == "real-1" })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

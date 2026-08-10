package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryContextServicesTest {
    @Test
    fun originalKnowledgeBuildsSearchableEntriesAndPreservesBoundary() {
        val root = createTempDirectory("zaomeng-original-knowledge")
        val storage = StorageService(root.toString().toPath())
        val runId = "run-test"
        val source = storage.getRunDirectory(runId) / "novel.txt"
        storage.writeTextAtomically(
            source,
            "世界的规则已经改变。林夕把秘密告诉了沈舟。沈舟没有告诉任何人。",
        )
        val manifest = buildJsonObject {
            put("run_id", runId)
            put("novel_path", source.toString())
        }
        val service = OriginalKnowledgeService(storage)

        service.ensure(manifest)
        val built = service.ensure(manifest, characterNames = listOf("林夕", "沈舟"))
        assertTrue((built["entry_count"]?.toString()?.toIntOrNull() ?: 0) > 0)
        val hits = service.search(manifest, "秘密", listOf("林夕", "沈舟"))
        assertTrue(hits.any { it["excerpt"].toString().contains("秘密") })

        val sceneHits = service.search(
            manifest,
            "不存在的当前消息",
            listOf("林夕"),
            sceneTerms = listOf("规则"),
        )
        assertTrue(sceneHits.isNotEmpty())

        service.updateBoundary(runId, "src-00001", "private", listOf("林夕"))
        val privateHit = service.search(manifest, "秘密", listOf("林夕", "沈舟"))
            .first { it["source_id"] == "src-00001" }
        assertEquals(listOf("林夕"), privateHit["allowed_characters"])
        assertEquals(listOf("沈舟"), privateHit["denied_characters"])
    }

    @Test
    fun longTermMemoryPersistsTurnsAndRetrievesRelevantText() {
        val root = createTempDirectory("zaomeng-long-term-memory")
        val storage = StorageService(root.toString().toPath())
        val service = LongTermMemoryService(storage)

        service.appendTurn(
            runId = "run-test",
            sessionId = "session-test",
            turnId = "turn-1",
            message = "我们约定在旧车站见面。",
            responses = listOf(mapOf("speaker" to "林夕", "message" to "我会带上钥匙。")),
        )
        val hits = service.search("run-test", "session-test", "旧车站")

        assertEquals(1, hits.size)
        assertTrue(hits.first()["text"].toString().contains("旧车站"))

        service.appendTurn(
            runId = "run-test",
            sessionId = "session-test",
            turnId = "turn-2",
            message = "后来他们去了码头。",
            responses = emptyList(),
        )
        service.copyForBranch(
            runId = "run-test",
            sourceSessionId = "session-test",
            targetSessionId = "branch-test",
            retainedTurnIds = setOf("turn-1"),
        )
        assertEquals(1, service.search("run-test", "branch-test", "旧车站").size)
        assertTrue(service.search("run-test", "branch-test", "码头").isEmpty())
    }

    @Test
    fun longTermMemoryKeepsRepeatedSpeakersAndReplacesCorrectedTurn() {
        val root = createTempDirectory("zaomeng-long-term-memory-correction")
        val storage = StorageService(root.toString().toPath())
        val service = LongTermMemoryService(storage)

        service.appendTurn(
            runId = "run-test",
            sessionId = "session-test",
            turnId = "turn-1",
            message = "继续。",
            responses = listOf(
                mapOf("speaker" to "林夕", "message" to "第一段旧回复。"),
                mapOf("speaker" to "林夕", "message" to "第二段旧回复。"),
            ),
        )
        assertEquals(2, service.search("run-test", "session-test", "旧回复", limit = 6).size)

        service.replaceTurn(
            runId = "run-test",
            sessionId = "session-test",
            turnId = "turn-1",
            message = "继续。",
            responses = listOf(mapOf("speaker" to "林夕", "message" to "修正后的回答。")),
        )

        assertTrue(service.search("run-test", "session-test", "旧回复", limit = 6).isEmpty())
        assertEquals(1, service.search("run-test", "session-test", "修正", limit = 6).size)
    }

    @Test
    fun worldMemoryAcceptsCurrentKnowledgeLedgerFieldNames() {
        val root = createTempDirectory("zaomeng-world-memory")
        val storage = StorageService(root.toString().toPath())
        val service = WorldMemoryService(storage)
        storage.writeRunManifest("run-test", buildJsonObject { put("run_id", "run-test") })

        service.syncCompletedTurn(
            runId = "run-test",
            sessionId = "session-test",
            turnId = "turn-1",
            title = "秘密被确认",
            participants = listOf("林夕"),
            events = emptyList(),
            location = "",
            timeHint = "",
            consistencyStatus = "pass",
            knowledgeLedger = listOf(buildJsonObject {
                put("fact", "钥匙在旧车站")
                put("holders", buildJsonArray { add(JsonPrimitive("林夕")) })
            }),
            updatedAt = "",
        )

        assertTrue(service.get("run-test").facts.any { it.summary == "钥匙在旧车站" })

        service.syncCompletedTurn(
            runId = "run-test",
            sessionId = "session-test",
            turnId = "turn-2",
            title = "秘密被转告",
            participants = listOf("林夕", "沈舟"),
            events = emptyList(),
            location = "",
            timeHint = "",
            consistencyStatus = "pass",
            knowledgeLedger = listOf(buildJsonObject {
                put("fact", "钥匙在旧车站")
                put("holders", buildJsonArray {
                    add(JsonPrimitive("林夕"))
                    add(JsonPrimitive("沈舟"))
                })
            }),
            updatedAt = "",
        )
        val updated = service.get("run-test").facts.first { it.summary == "钥匙在旧车站" }
        assertEquals(listOf("林夕", "沈舟"), updated.characters)
    }
}

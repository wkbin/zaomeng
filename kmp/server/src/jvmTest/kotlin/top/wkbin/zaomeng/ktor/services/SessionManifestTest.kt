package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 回归：创建会话后 manifest 必须写全字段（title/mode 等默认值不能省略）。 */
class SessionManifestTest {
    @Test
    fun `created session manifest contains title and mode`() {
        val dir = createTempDirectory("zaomeng-session-repro")
        try {
            val storage = StorageService(dir.toString().toPath())
            storage.writeRunManifest("run-1", buildJsonObject { put("run_id", "run-1"); put("title", "测试") })
            val service = SessionManagementService(storage, DialogueService(storage))

            val created = service.createDialogueSession(
                runId = "run-1",
                mode = "observe",
                participants = listOf("角色甲"),
            )
            val sessionId = created["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val loaded = storage.loadSessionManifest("run-1", sessionId)
            assertTrue(loaded.containsKey("title"), "manifest 缺少 title")
            assertTrue(loaded.containsKey("mode"), "manifest 缺少 mode")
            assertTrue(loaded.containsKey("status"), "manifest 缺少 status")
            assertTrue(loaded.containsKey("turn_count"), "manifest 缺少 turn_count")
            assertEquals("", loaded["title"]?.jsonPrimitive?.contentOrNull)
            assertEquals("observe", loaded["mode"]?.jsonPrimitive?.contentOrNull)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `insert session ignores controlled character from stale clients`() {
        val dir = createTempDirectory("zaomeng-insert-session")
        try {
            val storage = StorageService(dir.toString().toPath())
            storage.writeRunManifest("run-1", buildJsonObject { put("run_id", "run-1"); put("title", "测试") })
            val service = SessionManagementService(storage, DialogueService(storage))

            val created = service.createDialogueSession(
                runId = "run-1",
                mode = "insert",
                participants = listOf("潘金莲"),
                controlledCharacter = "潘金莲",
            )
            val sessionId = created["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val loaded = storage.loadSessionManifest("run-1", sessionId)
            assertEquals("", loaded["controlled_character"]?.jsonPrimitive?.contentOrNull)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `large transcript is archived while full session remains materialized`() {
        val dir = createTempDirectory("zaomeng-session-archive")
        try {
            val storage = StorageService(dir.toString().toPath())
            val runId = "run-1"
            val sessionId = "session-1"
            val base = buildJsonObject {
                put("run_id", runId)
                put("session_id", sessionId)
                put("transcript_start", 0)
            }
            val combined = buildJsonArray {
                repeat(121) { index ->
                    add(buildJsonObject {
                        put("speaker", "角色")
                        put("message", "消息$index")
                    })
                }
            }
            val compacted = storage.compactSessionTranscript(runId, sessionId, base, combined)
            val manifest = buildJsonObject {
                base.forEach { (key, value) -> put(key, value) }
                put("transcript", compacted.recent)
                put("transcript_start", compacted.startIndex)
                put("transcript_count", compacted.totalCount)
            }
            storage.writeTextAtomically(
                storage.getDialogueSessionManifestFile(runId, sessionId),
                kotlinx.serialization.json.Json.encodeToString(JsonObject.serializer(), manifest),
            )

            val lean = storage.loadSessionManifest(runId, sessionId)
            val full = storage.getDialogueSession(runId, sessionId)
            assertEquals(80, lean["transcript"]?.jsonArray?.size)
            assertEquals(41, lean["transcript_start"]?.jsonPrimitive?.intOrNull)
            assertEquals(121, full["transcript"]?.jsonArray?.size)
            assertEquals("消息0", full["transcript"]?.jsonArray?.first()?.jsonObject?.get("message")?.jsonPrimitive?.content)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

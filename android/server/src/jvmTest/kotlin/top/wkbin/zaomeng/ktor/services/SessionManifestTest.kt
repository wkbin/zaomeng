package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
}

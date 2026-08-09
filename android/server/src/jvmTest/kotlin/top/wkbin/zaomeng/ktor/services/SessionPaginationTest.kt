package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 会话列表分页测试：验证 offset/limit 切片、hasMore、搜索过滤（参与者/书卷标题）、
 * 按书名排序，以及按 run 的会话列表分页。
 */
class SessionPaginationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `recent sessions page by offset and limit`() {
        val dir = createTempDirectory("zaomeng-session-page")
        try {
            val storage = StorageService(dir.toString().toPath())
            storage.writeRunManifest("run-1", runManifest("run-1", "第一本"))
            repeat(5) { index ->
                writeSession(storage, "run-1", "s$index", "会话$index", "2026-08-0${index + 1}T00:00:00Z")
            }
            val service = SessionManagementService(storage, DialogueService(storage))

            val first = service.listRecentSessions(offset = 0, limit = 3)
            assertEquals(3, first.items.size)
            assertEquals(5, first.total)
            assertTrue(first.hasMore)
            assertEquals("s4", first.items[0]["session_id"]?.jsonPrimitive?.content)

            val second = service.listRecentSessions(offset = 3, limit = 3)
            assertEquals(2, second.items.size)
            assertEquals(5, second.total)
            assertFalse(second.hasMore)
            assertEquals("s1", second.items[0]["session_id"]?.jsonPrimitive?.content)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `query filters by participants and book title`() {
        val dir = createTempDirectory("zaomeng-session-page")
        try {
            val storage = StorageService(dir.toString().toPath())
            storage.writeRunManifest("run-1", runManifest("run-1", "红楼梦"))
            storage.writeRunManifest("run-2", runManifest("run-2", "西游记"))
            writeSession(
                storage, "run-1", "s1", "大观园", "2026-08-01T00:00:00Z",
                participants = listOf("贾宝玉", "林黛玉"),
            )
            writeSession(
                storage, "run-2", "s2", "花果山", "2026-08-02T00:00:00Z",
                participants = listOf("孙悟空"),
            )
            val service = SessionManagementService(storage, DialogueService(storage))

            val byParticipant = service.listRecentSessions(offset = 0, limit = 50, query = "黛玉")
            assertEquals(listOf("s1"), byParticipant.items.map { it["session_id"]?.jsonPrimitive?.content })

            val byBook = service.listRecentSessions(offset = 0, limit = 50, query = "西游")
            assertEquals(listOf("s2"), byBook.items.map { it["session_id"]?.jsonPrimitive?.content })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `title sort groups by book title`() {
        val dir = createTempDirectory("zaomeng-session-page")
        try {
            val storage = StorageService(dir.toString().toPath())
            storage.writeRunManifest("run-beta", runManifest("run-beta", "Beta书"))
            storage.writeRunManifest("run-alpha", runManifest("run-alpha", "Alpha书"))
            writeSession(storage, "run-beta", "s1", "会话一", "2026-08-01T00:00:00Z")
            writeSession(storage, "run-alpha", "s2", "会话二", "2026-08-02T00:00:00Z")
            val service = SessionManagementService(storage, DialogueService(storage))

            val result = service.listRecentSessions(offset = 0, limit = 50, sort = "title")

            assertEquals(
                listOf("s2", "s1"),
                result.items.map { it["session_id"]?.jsonPrimitive?.content },
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `run scoped listing slices and pages`() {
        val dir = createTempDirectory("zaomeng-session-page")
        try {
            val storage = StorageService(dir.toString().toPath())
            storage.writeRunManifest("run-1", runManifest("run-1", "第一本"))
            repeat(4) { index ->
                writeSession(storage, "run-1", "s$index", "会话$index", "2026-08-0${index + 1}T00:00:00Z")
            }
            val service = SessionManagementService(storage, DialogueService(storage))

            val page = service.listDialogueSessions(runId = "run-1", offset = 2, limit = 2)

            assertEquals(2, page.items.size)
            assertEquals(4, page.total)
            assertFalse(page.hasMore)
            assertEquals(listOf("s1", "s0"), page.items.map { it["session_id"]?.jsonPrimitive?.content })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun runManifest(runId: String, title: String): JsonObject = buildJsonObject {
        put("run_id", runId)
        put("title", title)
        put("novel_id", "novel-$runId")
        put("status", "ready")
    }

    private fun writeSession(
        storage: StorageService,
        runId: String,
        sessionId: String,
        title: String,
        updatedAt: String,
        participants: List<String> = listOf("角色甲"),
        controlledCharacter: String = "",
    ) {
        val manifest = buildJsonObject {
            put("session_id", sessionId)
            put("run_id", runId)
            put("title", title)
            put("mode", "observe")
            put("status", "ready")
            put("participants", buildJsonArray { participants.forEach { add(JsonPrimitive(it)) } })
            if (controlledCharacter.isNotBlank()) put("controlled_character", controlledCharacter)
            put("last_entry_preview", "最近的一条消息预览")
            put("transcript", buildJsonArray { })
            put("updated_at", updatedAt)
        }
        storage.writeTextAtomically(
            storage.getDialogueSessionManifestFile(runId, sessionId),
            json.encodeToString(JsonObject.serializer(), manifest),
        )
    }
}

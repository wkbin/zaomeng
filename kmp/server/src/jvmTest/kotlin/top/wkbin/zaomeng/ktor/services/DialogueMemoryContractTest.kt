package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 回归：会话记忆写入/删除必须与 Python 基线一致——
 * 更新或删除不存在的记忆要报错，启用数超限时不能用静默停用旧记忆来“兜底”。
 */
class DialogueMemoryContractTest {
    @Test
    fun `updating an unknown memory id is rejected`() {
        withSession(ledger = listOf(memory("mem-1", text = "旧记忆"))) { storage, service ->
            val error = assertFailsWith<IllegalArgumentException> {
                service.saveMemory("run-1", "sess-1", "mem-missing", "新内容", "story", false, true)
            }
            assertEquals("指定的记忆不存在。", error.message)
            assertEquals(listOf("mem-1"), memoryIds(storage, "sess-1"))
        }
    }

    @Test
    fun `deleting an unknown memory id is rejected`() {
        withSession(ledger = listOf(memory("mem-1", text = "旧记忆"))) { storage, service ->
            val error = assertFailsWith<IllegalArgumentException> {
                service.deleteMemory("run-1", "sess-1", "mem-missing")
            }
            assertEquals("指定的记忆不存在。", error.message)
            assertEquals(listOf("mem-1"), memoryIds(storage, "sess-1"))
        }
    }

    @Test
    fun `enabling more than twenty memories is rejected without touching the ledger`() {
        val full = (1..20).map { memory("mem-$it", text = "记忆$it") }
        withSession(ledger = full) { storage, service ->
            val error = assertFailsWith<IllegalArgumentException> {
                service.saveMemory("run-1", "sess-1", "", "第二十一条", "story", false, true)
            }
            assertTrue(error.message.orEmpty().contains("最多为 20 条"), "应返回启用上限错误")
            val manifest = storage.loadSessionManifest("run-1", "sess-1")
            val ledger = manifest.getValue("memory_ledger").jsonArray.map { it.jsonObject }
            assertEquals(20, ledger.size, "超限时不应写入新记忆")
            assertTrue(ledger.all { it.getValue("enabled").jsonPrimitive.content.toBoolean() }, "不应静默停用旧记忆")
        }
    }

    @Test
    fun `updating an existing memory preserves its created time`() {
        withSession(ledger = listOf(memory("mem-1", text = "旧记忆"))) { storage, service ->
            service.saveMemory("run-1", "sess-1", "mem-1", "新记忆", "long_term", true, true)
            val entry = storage.loadSessionManifest("run-1", "sess-1")
                .getValue("memory_ledger").jsonArray.single().jsonObject
            assertEquals("新记忆", entry.getValue("text").jsonPrimitive.content)
            assertEquals("long_term", entry.getValue("category").jsonPrimitive.content)
            assertEquals("2026-01-01T00:00:00Z", entry.getValue("created_at").jsonPrimitive.content)
        }
    }

    private fun withSession(ledger: List<kotlinx.serialization.json.JsonObject>, block: (StorageService, DialogueAdvancedService) -> Unit) {
        val dir = Files.createTempDirectory("zaomeng-memory-contract").toFile()
        try {
            val storage = StorageService(dir.absolutePath.toPath())
            storage.writeRunManifest("run-1", buildJsonObject { put("run_id", "run-1") })
            storage.writeTextAtomically(
                storage.getDialogueSessionManifestFile("run-1", "sess-1"),
                buildJsonObject {
                    put("session_id", "sess-1")
                    put("memory_ledger", buildJsonArray { ledger.forEach { add(it) } })
                }.toString(),
            )
            block(storage, DialogueAdvancedService(storage, llm = null, prompts = null))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun memory(id: String, text: String) = buildJsonObject {
        put("memory_id", id)
        put("text", text)
        put("category", "story")
        put("pinned", false)
        put("enabled", true)
        put("created_at", "2026-01-01T00:00:00Z")
        put("updated_at", "2026-01-01T00:00:00Z")
    }

    private fun memoryIds(storage: StorageService, sessionId: String): List<String> =
        storage.loadSessionManifest("run-1", sessionId)
            .getValue("memory_ledger").jsonArray
            .map { it.jsonObject.getValue("memory_id").jsonPrimitive.content }
}

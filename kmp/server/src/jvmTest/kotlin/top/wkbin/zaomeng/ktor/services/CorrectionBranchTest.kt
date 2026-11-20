package top.wkbin.zaomeng.ktor.services

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.platform.FileSecureKeyValueStore
import top.wkbin.zaomeng.platform.PromptSource
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 回归：修正最新回复必须新建 `consistency_correction` 分支，源会话保持不变。
 */
class CorrectionBranchTest {
    private class StubPromptSource : PromptSource {
        override fun read(relativePath: String): Pair<String, Long>? = "key: value" to 1000L
        override fun lastModified(relativePath: String): Long? = 1000L
    }

    @Test
    fun `correct latest forks before the target turn and keeps the source session`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("zaomeng-correction-branch").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requestBody = ""
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.use {
                requestBody = it.requestBody.readBytes().decodeToString()
                val response = buildJsonObject {
                    put("model", "local")
                    put(
                        "choices",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("index", 0)
                                    put(
                                        "message",
                                        buildJsonObject {
                                            put("role", "assistant")
                                            put("content", """{"speaker":"角色甲","message":"修正后的回答"}""")
                                        },
                                    )
                                },
                            )
                        },
                    )
                }.toString().encodeToByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(200, response.size.toLong())
                it.responseBody.write(response)
            }
        }
        server.start()
        try {
            val root = directory.absolutePath.toPath()
            val storage = StorageService(root)
            val keys = ModelApiKeyService(FileSecureKeyValueStore(root / "test.properties"))
            SettingsManagementService(storage, keys).saveModelSettings(
                provider = "openai-compatible",
                model = "local",
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                apiKey = "test-key",
            )
            val client = LlmClient(keys, storage)
            try {
                val runId = "run-1"
                val sessionId = "sess-1"
                storage.writeRunManifest(
                    runId,
                    buildJsonObject {
                        put("run_id", runId)
                        put("title", "测试")
                    },
                )
                val transcript = buildJsonArray {
                    add(entry("旅人", "第一问", "user", "turn-1"))
                    add(entry("角色甲", "旧回答一", "character", "turn-1"))
                    add(entry("旅人", "第二问", "user", "turn-2"))
                    add(entry("角色甲", "旧回答二", "character", "turn-2"))
                }
                storage.writeTextAtomically(
                    storage.getDialogueSessionManifestFile(runId, sessionId),
                    buildJsonObject {
                        put("session_id", sessionId)
                        put("run_id", runId)
                        put("title", "测试会话")
                        put("mode", "observe")
                        put("status", "ready")
                        put("participants", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("角色甲")) })
                        put("revision", 1)
                        put("transcript_start", 0)
                        put("transcript_count", 4)
                        put("turn_count", 2)
                        put("current_turn_id", "turn-2")
                        put("last_entry_preview", "旧回答二")
                        put("transcript", transcript)
                        put(
                            "consistency_monitor",
                            buildJsonObject {
                                put(
                                    "latest",
                                    buildJsonObject {
                                        put("turn_id", "turn-2")
                                        put("issues", buildJsonArray { add(buildJsonObject { put("type", "fact") }) })
                                    },
                                )
                                put("history", buildJsonArray {})
                                put("checked_turns", 2)
                                put("issue_count", 1)
                                put("knowledge_ledger", buildJsonArray {})
                            },
                        )
                    }.toString(),
                )

                val service = DialogueAdvancedService(
                    storage = storage,
                    llm = client,
                    prompts = PromptLoader(StubPromptSource()),
                )
                val branch = service.correctLatest(runId, sessionId)
                val branchId = branch.getValue("session_id").jsonPrimitive.content
                assertNotEquals(sessionId, branchId, "修正必须创建新会话")

                val source = storage.getDialogueSession(runId, sessionId)
                assertEquals(4, source.getValue("transcript").jsonArray.size, "源会话 transcript 不应被修改")
                assertEquals("旧回答二", messagesOf(source).last())
                assertEquals(1, source.getValue("revision").jsonPrimitive.content.toInt(), "源会话 revision 不应变化")

                val origin = branch.getValue("branch_origin").jsonObject
                assertEquals(sessionId, origin.getValue("session_id").jsonPrimitive.contentOrNull)
                assertEquals("consistency_correction", origin.getValue("kind").jsonPrimitive.contentOrNull)
                assertEquals("turn-2", origin.getValue("turn_id").jsonPrimitive.contentOrNull)

                val branchSession = storage.getDialogueSession(runId, branchId)
                assertEquals(
                    listOf("第一问", "旧回答一", "第二问", "修正后的回答"),
                    messagesOf(branchSession),
                    "分支应保留目标回合之前的内容，并重新生成该回合回复",
                )
                assertEquals(2, branchSession.getValue("turn_count").jsonPrimitive.content.toInt())
                val branchMonitor = branchSession.getValue("consistency_monitor").jsonObject
                assertTrue(branchMonitor.getValue("latest").jsonObject.isEmpty(), "新分支不应继承旧的审校问题")

                assertTrue(requestBody.contains("第二问"), "生成请求应携带被修正回合的用户输入")
                assertTrue(requestBody.contains("correction_context"), "生成请求应注入修正上下文")
                assertTrue(requestBody.contains("original_responses"), "修正上下文应携带原始回复供模型对照")
            } finally {
                client.close()
            }
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    private fun entry(speaker: String, message: String, role: String, turnId: String): JsonObject =
        buildJsonObject {
            put("speaker", speaker)
            put("message", message)
            put("role", role)
            put("turn_id", turnId)
            put("timestamp", "2026-01-01T00:00:00Z")
        }

    private fun messagesOf(session: JsonObject): List<String> =
        session.getValue("transcript").jsonArray.mapNotNull {
            it.jsonObject["message"]?.jsonPrimitive?.contentOrNull
        }
}

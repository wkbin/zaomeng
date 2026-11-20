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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 回归：深度复核只针对最新回合，并与已有确定性报告合并；
 * 期间提交新回合时旧结论必须过期，不能覆盖最新状态。
 */
class DeepReviewContractTest {
    private class StubPromptSource : PromptSource {
        override fun read(relativePath: String): Pair<String, Long>? =
            "system_prompt: 复核\noutput_format: json" to 1000L
        override fun lastModified(relativePath: String): Long? = 1000L
    }

    @Test
    fun `deep review merges semantic issues into the deterministic report`() =
        withServer(review = """{"issues":[{"code":"semantic_voice_drift","severity":"warning","speaker":"角色甲","detail":"与人物资料冲突","evidence":"回复原文"}],"summary":"语义复核结论"}""") { storage, service, handlerBody ->
            service.deepReview("run-1", "sess-1")
            val monitor = storage.loadSessionManifest("run-1", "sess-1")
                .getValue("consistency_monitor").jsonObject
            val latest = monitor.getValue("latest").jsonObject
            assertEquals("turn-2", latest.getValue("turn_id").jsonPrimitive.content)
            assertEquals("语义复核结论", latest.getValue("summary").jsonPrimitive.content)
            assertEquals(2, latest.getValue("issues").jsonArray.size, "确定性问题和语义问题都要保留")
            assertTrue(latest.getValue("issues").jsonArray.any { it.jsonObject["source"]?.jsonPrimitive?.contentOrNull == "semantic_review" })
            assertEquals("warning", latest.getValue("status").jsonPrimitive.content)
            assertEquals(65, latest.getValue("score").jsonPrimitive.content.toInt(), "error 扣 25、warning 扣 10")
            assertTrue(latest.getValue("coverage").jsonObject.getValue("semantic_review").jsonPrimitive.content.toBoolean())
            assertEquals(2, monitor.getValue("issue_count").jsonPrimitive.content.toInt())
            assertEquals(1, monitor.getValue("semantic_review_count").jsonPrimitive.content.toInt())
            val history = monitor.getValue("history").jsonArray
            assertEquals(1, history.size, "同一回合重复复核不应在历史里生成重复报告")
            assertEquals("turn-2", history.single().jsonObject.getValue("turn_id").jsonPrimitive.content)
            assertTrue(handlerBody().contains("回复原文") || handlerBody().contains("旧回复二"), "复核请求应携带最新回合的回复")
        }

    @Test
    fun `deep review is rejected when a new turn lands during the review`() =
        withServer(review = """{"issues":[],"summary":"无问题"}""", mutateBeforeResponse = true) { storage, service, _ ->
            val error = assertFailsWith<IllegalArgumentException> {
                service.deepReview("run-1", "sess-1")
            }
            assertTrue(error.message.orEmpty().contains("已过期"))
            val monitor = storage.loadSessionManifest("run-1", "sess-1")
                .getValue("consistency_monitor").jsonObject
            assertEquals("turn-2", monitor.getValue("latest").jsonObject.getValue("turn_id").jsonPrimitive.content)
        }

    @Test
    fun `empty review response does not overwrite the previous report`() =
        withServer(review = "{}") { storage, service, _ ->
            assertFailsWith<IllegalStateException> {
                service.deepReview("run-1", "sess-1")
            }
            val monitor = storage.loadSessionManifest("run-1", "sess-1")
                .getValue("consistency_monitor").jsonObject
            assertEquals("turn-2", monitor.getValue("latest").jsonObject.getValue("turn_id").jsonPrimitive.content)
            assertEquals(1, monitor.getValue("issue_count").jsonPrimitive.content.toInt())
        }

    private fun withServer(
        review: String,
        mutateBeforeResponse: Boolean = false,
        block: suspend (StorageService, DialogueAdvancedService, () -> String) -> Unit,
    ) = runBlocking<Unit> {
        val directory = Files.createTempDirectory("zaomeng-deep-review").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var captured = ""
        lateinit var storage: StorageService
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.use {
                captured = it.requestBody.readBytes().decodeToString()
                if (mutateBeforeResponse) {
                    storage.updateSessionManifest("run-1", "sess-1") { manifest ->
                        val transcript = manifest.getValue("transcript").jsonArray.toMutableList()
                        transcript.add(
                            buildJsonObject {
                                put("speaker", "旅人")
                                put("message", "第三问")
                                put("role", "user")
                                put("turn_id", "turn-3")
                                put("timestamp", "2026-01-01T00:01:00Z")
                            },
                        )
                        buildJsonObject {
                            manifest.forEach { (key, value) -> put(key, value) }
                            put("transcript", buildJsonArray { transcript.forEach { add(it) } })
                            put("transcript_count", transcript.size)
                        }
                    }
                }
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
                                            put("content", review)
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
            storage = StorageService(root)
            val keys = ModelApiKeyService(FileSecureKeyValueStore(root / "test.properties"))
            SettingsManagementService(storage, keys).saveModelSettings(
                provider = "openai-compatible",
                model = "local",
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                apiKey = "test-key",
            )
            val client = LlmClient(keys, storage)
            try {
                storage.writeRunManifest("run-1", buildJsonObject { put("run_id", "run-1") })
                writeSession(storage)
                val service = DialogueAdvancedService(
                    storage = storage,
                    llm = client,
                    prompts = PromptLoader(StubPromptSource()),
                )
                block(storage, service) { captured }
            } finally {
                client.close()
            }
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    private fun writeSession(storage: StorageService) {
        storage.writeTextAtomically(
            storage.getDialogueSessionManifestFile("run-1", "sess-1"),
            buildJsonObject {
                put("session_id", "sess-1")
                put("run_id", "run-1")
                put("participants", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("角色甲")) })
                put("transcript_start", 0)
                put("transcript_count", 4)
                put("turn_count", 2)
                put("current_turn_id", "turn-2")
                put("transcript", buildJsonArray {
                    add(entry("旅人", "第一问", "user", "turn-1"))
                    add(entry("角色甲", "旧回复一", "character", "turn-1"))
                    add(entry("旅人", "第二问", "user", "turn-2"))
                    add(entry("角色甲", "旧回复二", "character", "turn-2"))
                })
                put("consistency_monitor", buildJsonObject {
                    put("latest", buildJsonObject {
                        put("turn_id", "turn-2")
                        put("status", "warning")
                        put("score", 75)
                        put("issues", buildJsonArray {
                            add(buildJsonObject {
                                put("code", "deterministic_fact")
                                put("severity", "error")
                                put("speaker", "角色甲")
                                put("evidence", "旧回复二")
                            })
                        })
                        put("coverage", buildJsonObject { put("deterministic", true) })
                    })
                    put("history", buildJsonArray {})
                    put("checked_turns", 2)
                    put("issue_count", 1)
                })
            }.toString(),
        )
    }

    private fun entry(speaker: String, message: String, role: String, turnId: String): JsonObject =
        buildJsonObject {
            put("speaker", speaker)
            put("message", message)
            put("role", role)
            put("turn_id", turnId)
            put("timestamp", "2026-01-01T00:00:00Z")
        }
}

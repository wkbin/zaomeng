package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.ktor.models.DialogueResponse
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginRuleEngineTest {
    private val json = Json { prettyPrint = true }

    @Test
    fun `rules inject generation directives and keep state per session`() {
        val directory = createTempDirectory("plugin-rule-engine")
        try {
            val storage = StorageService(directory.toString().toPath())
            val pluginId = "story-rules"
            val pluginDirectory = storage.getStorageRoot() / "plugins" / pluginId
            storage.mkdirs(pluginDirectory)
            storage.writeTextAtomically(pluginDirectory / "plugin.json", json.encodeToString(JsonObject.serializer(), manifest(pluginId)))
            storage.writeTextAtomically(
                storage.getStorageRoot() / "plugins" / "enabled.json",
                """{"enabled":["$pluginId"]}""",
            )
            createSession(storage, "run-1", "session-a", turnCount = 4)
            createSession(storage, "run-1", "session-b", turnCount = 4)
            val engine = PluginRuleEngine(storage, PluginService(storage))

            val prepared = engine.beforeGeneration(
                runId = "run-1",
                sessionId = "session-a",
                turnId = "turn-5",
                message = "我拒绝这笔交易",
                session = storage.loadSessionManifest("run-1", "session-a"),
            )

            assertEquals(
                "让神秘商人进入场景，并回应：我拒绝这笔交易",
                prepared["plugin_rule_directives"]!!.jsonObject["$pluginId/merchant"]!!.jsonPrimitive.content,
            )
            assertFalse("plugin_rule_directives" in storage.loadSessionManifest("run-1", "session-a"))

            engine.afterTurn(
                runId = "run-1",
                sessionId = "session-a",
                turnId = "turn-5",
                message = "我拒绝这笔交易",
                responses = listOf(DialogueResponse("商人", "那就下次再谈。")),
            )
            engine.afterTurn(
                runId = "run-1",
                sessionId = "session-a",
                turnId = "turn-5",
                message = "我拒绝这笔交易",
                responses = listOf(DialogueResponse("商人", "那就下次再谈。")),
            )

            val stateA = storage.loadSessionManifest("run-1", "session-a")["plugin_rule_states"]!!
                .jsonObject[pluginId]!!.jsonObject
            assertEquals("1", stateA["refusals"]!!.jsonPrimitive.content)
            assertFalse("plugin_rule_states" in storage.loadSessionManifest("run-1", "session-b"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun manifest(pluginId: String) = buildJsonObject {
        put("id", pluginId)
        put("name", "剧情规则")
        put("version", "0.1.0")
        put("apiVersion", "2")
        put("permissions", buildJsonArray {
            add(JsonPrimitive("chat.context.read"))
            add(JsonPrimitive("generation.enhance"))
            add(JsonPrimitive("model.invoke"))
            add(JsonPrimitive("chat.state.write"))
        })
        put("contributes", buildJsonObject {
            put("generationEnhancers", buildJsonArray {
                add(buildJsonObject { put("id", "main"); put("title", "剧情规则") })
            })
        })
        put("execution", buildJsonObject {
            put("mode", "declarative")
            put("generationEnhancers", buildJsonObject {
                put("main", buildJsonObject { put("rule", "保持剧情连贯。") })
            })
            put("rules", buildJsonArray {
                add(buildJsonObject {
                    put("id", "merchant")
                    put("title", "商人登场")
                    put("event", "before_generation")
                    put("match", buildJsonObject {
                        put("keywords", buildJsonArray { add(JsonPrimitive("拒绝")) })
                        put("everyTurns", 5)
                    })
                    put("actions", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "add_instruction")
                            put("instruction", "让神秘商人进入场景，并回应：{{message}}")
                        })
                    })
                })
                add(buildJsonObject {
                    put("id", "count-refusal")
                    put("title", "记录拒绝")
                    put("event", "after_turn")
                    put("match", buildJsonObject { put("keywords", buildJsonArray { add(JsonPrimitive("拒绝")) }) })
                    put("actions", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "increment_state")
                            put("key", "refusals")
                            put("amount", 1)
                        })
                    })
                })
            })
        })
    }

    private fun createSession(storage: StorageService, runId: String, sessionId: String, turnCount: Int) {
        val runDirectory = storage.getRunDirectory(runId)
        storage.mkdirs(runDirectory)
        storage.writeRunManifest(runId, buildJsonObject { put("run_id", runId) })
        val file = storage.getDialogueSessionManifestFile(runId, sessionId)
        storage.mkdirs(file.parent!!)
        storage.writeTextAtomically(file, json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("session_id", sessionId)
            put("run_id", runId)
            put("title", "测试")
            put("mode", "observe")
            put("turn_count", turnCount)
            put("participants", buildJsonArray { add(JsonPrimitive("商人")) })
            put("transcript", buildJsonArray {})
        }))
    }
}

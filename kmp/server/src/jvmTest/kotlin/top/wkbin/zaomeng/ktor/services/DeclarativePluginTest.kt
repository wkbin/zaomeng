package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.plugins.api.ChatActionRequest
import top.wkbin.zaomeng.plugins.api.NpcGeneratorRequest
import top.wkbin.zaomeng.plugins.api.PluginHost
import top.wkbin.zaomeng.plugins.api.PluginPersonaSummary
import top.wkbin.zaomeng.plugins.api.PluginReplyAsCharacterResult
import top.wkbin.zaomeng.plugins.api.SuggestionOption

class DeclarativePluginTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyPythonManifestIsNotExecutable() {
        val raw = json.parseToJsonElement(
            """
            {
              "id": "com.example.legacy",
              "name": "旧插件",
              "entry": "main.py",
              "contributes": {
                "chatActions": [{"id": "act", "title": "动作"}]
              }
            }
            """.trimIndent(),
        ).jsonObject

        val evaluation = DeclarativePluginLoader.evaluate("com.example.legacy", raw)

        assertFalse(evaluation.executable)
        assertEquals("unsupported", evaluation.executionMode)
        assertEquals(null, evaluation.plugin)
    }

    @Test
    fun incompatibleApiVersionIsRejected() {
        val raw = json.parseToJsonElement(
            """
            {
              "id": "com.example.future",
              "name": "未来插件",
              "apiVersion": "3",
              "permissions": ["chat.context.read", "chat.draft.write", "model.invoke"],
              "contributes": {"chatActions": [{"id": "act", "title": "动作"}]},
              "execution": {
                "mode": "declarative",
                "chatActions": {"act": {"operation": "suggest", "direction": "继续"}}
              }
            }
            """.trimIndent(),
        ).jsonObject

        val evaluation = DeclarativePluginLoader.evaluate("com.example.future", raw)

        assertFalse(evaluation.compatible)
        assertFalse(evaluation.executable)
        assertEquals("incompatible", evaluation.executionMode)
    }

    @Test
    fun declarativeChatActionRendersConfigAndCallsHost() = runBlocking {
        val raw = json.parseToJsonElement(
            """
            {
              "id": "com.example.quick",
              "name": "快捷接话",
              "version": "1.0.0",
              "apiVersion": "2",
              "permissions": ["chat.context.read", "chat.draft.write", "model.invoke"],
              "settings": [{"key": "tone", "title": "语气", "type": "string", "default": "克制"}],
              "contributes": {
                "chatActions": [{"id": "quick", "title": "快捷接话", "placement": "composer", "icon": "sparkles"}]
              },
              "execution": {
                "mode": "declarative",
                "chatActions": {
                  "quick": {
                    "operation": "suggest",
                    "direction": "保持{{config.tone}}，基于草稿：{{seed_text}}",
                    "empty_notice": "没有生成可用草稿"
                  }
                }
              }
            }
            """.trimIndent(),
        ).jsonObject

        val evaluation = DeclarativePluginLoader.evaluate("com.example.quick", raw)
        assertTrue(evaluation.executable)
        val plugin = assertNotNull(evaluation.plugin)

        val result = plugin.executeChatAction(
            actionId = "quick",
            request = ChatActionRequest(
                runId = "run-1",
                sessionId = "sess-1",
                seedText = "你好",
                config = mapOf("tone" to "温柔"),
            ),
            host = FakePluginHost(),
        )

        assertEquals("保持温柔，基于草稿：你好", result.suggestion)
    }

    @Test
    fun declarativeNpcGeneratorCallsHost() = runBlocking {
        val raw = json.parseToJsonElement(
            """
            {
              "id": "com.example.npc",
              "name": "随机来客",
              "permissions": ["chat.context.read", "chat.cast.write", "model.invoke"],
              "contributes": {
                "temporaryNpcGenerators": [{"id": "generate", "title": "随机来客"}]
              },
              "execution": {
                "mode": "declarative",
                "temporaryNpcGenerators": {
                  "generate": {
                    "direction": "生成一名神秘来客：{{direction}}",
                    "notice": "来客入场"
                  }
                }
              }
            }
            """.trimIndent(),
        ).jsonObject

        val plugin = assertNotNull(DeclarativePluginLoader.evaluate("com.example.npc", raw).plugin)
        val result = plugin.generateTemporaryNpc(
            generatorId = "generate",
            request = NpcGeneratorRequest(runId = "run-1", sessionId = "sess-1", direction = "带着秘密"),
            host = FakePluginHost(),
        )

        assertEquals("张三", result.npc["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("来客入场", result.notice)
    }

    @Test
    fun missingDeclarativeRecipeIsRejected() {
        val raw = json.parseToJsonElement(
            """
            {
              "id": "com.example.broken",
              "name": "缺失配方",
              "contributes": {
                "chatActions": [{"id": "act", "title": "动作"}]
              },
              "execution": {
                "mode": "declarative",
                "chatActions": {}
              }
            }
            """.trimIndent(),
        ).jsonObject

        val evaluation = DeclarativePluginLoader.evaluate("com.example.broken", raw)

        assertFalse(evaluation.executable)
        assertEquals("declarative-invalid", evaluation.executionMode)
    }

    @Test
    fun declarativeGenerationEnhancerIsParsed() {
        val raw = json.parseToJsonElement(
            """
            {
              "id": "com.example.enhancer",
              "name": "增强示例",
              "permissions": ["chat.context.read", "generation.enhance", "model.invoke"],
              "contributes": {
                "generationEnhancers": [{"id": "inner-voice", "title": "内心声音"}]
              },
              "execution": {
                "mode": "declarative",
                "generationEnhancers": {
                  "inner-voice": {
                    "rule": "为每个发言角色增加一句简短内心独白"
                  }
                }
              }
            }
            """.trimIndent(),
        ).jsonObject

        val evaluation = DeclarativePluginLoader.evaluate("com.example.enhancer", raw)

        assertTrue(evaluation.executable)
        assertEquals(
            "为每个发言角色增加一句简短内心独白",
            evaluation.generationRecipes["inner-voice"]?.rule,
        )
    }

    @Test
    fun storageRecipesReadAndWritePluginScopedData() = runBlocking {
        val raw = json.parseToJsonElement(
            """
            {
              "id": "com.example.storage",
              "name": "存储插件",
              "permissions": ["storage.read", "storage.write"],
              "contributes": {
                "chatActions": [
                  {"id": "save", "title": "保存"},
                  {"id": "load", "title": "读取"}
                ]
              },
              "execution": {
                "mode": "declarative",
                "chatActions": {
                  "save": {
                    "operation": "storage_set",
                    "key": "notes",
                    "value": "{{seed_text}}"
                  },
                  "load": {
                    "operation": "storage_get",
                    "key": "notes"
                  }
                }
              }
            }
            """.trimIndent(),
        ).jsonObject
        val plugin = assertNotNull(DeclarativePluginLoader.evaluate("com.example.storage", raw).plugin)
        val host = FakePluginHost()

        plugin.executeChatAction("save", ChatActionRequest(seedText = "记住这个秘密"), host)
        val loaded = plugin.executeChatAction("load", ChatActionRequest(), host)

        assertEquals("记住这个秘密", loaded.suggestion)
        assertEquals("记住这个秘密", host.data["notes"])
    }

    @Test
    fun httpRecipeCallsHostWithResolvedUrlAndBody() = runBlocking {
        val raw = json.parseToJsonElement(
            """
            {
              "id": "com.example.http",
              "name": "网络插件",
              "permissions": ["network.access"],
              "contributes": {
                "chatActions": [{"id": "fetch", "title": "抓取"}]
              },
              "execution": {
                "mode": "declarative",
                "chatActions": {
                  "fetch": {
                    "operation": "http_post",
                    "url": "https://example.com/api",
                    "body": "{{seed_text}}"
                  }
                }
              }
            }
            """.trimIndent(),
        ).jsonObject
        val plugin = assertNotNull(DeclarativePluginLoader.evaluate("com.example.http", raw).plugin)
        val host = FakePluginHost()

        val result = plugin.executeChatAction(
            actionId = "fetch",
            request = ChatActionRequest(seedText = "ping"),
            host = host,
        )

        assertEquals("POST https://example.com/api ping", result.suggestion)
    }

    @Test
    fun replyAsCharacterReturnsSelectedSpeaker() = runBlocking {
        val raw = json.parseToJsonElement(
            """
            {
              "id": "com.example.reply-as-character",
              "name": "替身回复",
              "permissions": ["chat.context.read", "chat.draft.write", "model.invoke", "run.personas.read"],
              "contributes": {
                "chatActions": [{"id": "reply", "title": "替我回"}]
              },
              "execution": {
                "mode": "declarative",
                "chatActions": {
                  "reply": {
                    "operation": "reply_as_character",
                    "direction": "替用户回复"
                  }
                }
              }
            }
            """.trimIndent(),
        ).jsonObject
        val plugin = assertNotNull(DeclarativePluginLoader.evaluate("com.example.reply-as-character", raw).plugin)
        val host = FakePluginHost().apply {
            replyAsCharacter = PluginReplyAsCharacterResult(character = "林黛玉", text = "你又来拿我取笑了。")
        }

        val result = plugin.executeChatAction("reply", ChatActionRequest(runId = "run-1", sessionId = "sess-1"), host)

        assertEquals("林黛玉", result.character)
        assertEquals("你又来拿我取笑了。", result.suggestion)
    }

    @Test
    fun muteCharacterReturnsUpdatedSession() = runBlocking {
        val raw = json.parseToJsonElement(
            """
            {
              "id": "com.example.mute",
              "name": "禁言插件",
              "permissions": ["chat.cast.write"],
              "contributes": {
                "chatActions": [{"id": "mute", "title": "禁言"}]
              },
              "execution": {
                "mode": "declarative",
                "chatActions": {
                  "mute": {
                    "operation": "mute_character",
                    "character": "{{seed_text}}"
                  }
                }
              }
            }
            """.trimIndent(),
        ).jsonObject
        val plugin = assertNotNull(DeclarativePluginLoader.evaluate("com.example.mute", raw).plugin)
        val host = FakePluginHost().apply {
            mutedSession = JsonObject(mapOf("session_id" to JsonPrimitive("sess-1"), "muted_characters" to JsonPrimitive("张三")))
        }

        val result = plugin.executeChatAction(
            actionId = "mute",
            request = ChatActionRequest(runId = "run-1", sessionId = "sess-1", seedText = "张三"),
            host = host,
        )

        assertEquals("sess-1", result.session["session_id"]?.jsonPrimitive?.contentOrNull)
        assertTrue(result.notice.contains("张三"))
    }

    private class FakePluginHost : PluginHost {
        val data = mutableMapOf<String, String>()
        var replyAsCharacter: PluginReplyAsCharacterResult? = null
        var mutedSession: JsonObject? = null

        override suspend fun invokeSuggestion(
            runId: String,
            sessionId: String,
            seedText: String,
            direction: String,
        ): String = direction

        override suspend fun invokeVariants(
            runId: String,
            sessionId: String,
            seedText: String,
            direction: String,
        ): List<SuggestionOption> = emptyList()

        override suspend fun invokeNpc(
            runId: String,
            sessionId: String,
            direction: String,
        ): JsonObject = JsonObject(mapOf("name" to JsonPrimitive("张三")))

        override suspend fun readPluginData(pluginId: String, key: String): String? = data[key]

        override suspend fun writePluginData(pluginId: String, key: String, value: String) {
            data[key] = value
        }

        override suspend fun invokeHttp(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String,
        ): String = "$method $url $body".trim()

        override suspend fun listRunPersonas(runId: String): List<PluginPersonaSummary> = emptyList()

        override suspend fun invokeReplyAsCharacter(
            runId: String,
            sessionId: String,
            character: String,
            seedText: String,
            direction: String,
        ): PluginReplyAsCharacterResult? = replyAsCharacter

        override suspend fun setSessionCharacterMuted(
            runId: String,
            sessionId: String,
            character: String,
            muted: Boolean,
        ): JsonObject? = mutedSession

        override fun log(pluginId: String, level: String, message: String) = Unit
    }
}

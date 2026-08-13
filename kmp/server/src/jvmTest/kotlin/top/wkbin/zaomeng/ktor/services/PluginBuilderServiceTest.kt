package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import top.wkbin.zaomeng.data.api.PluginPackageInspectionDto
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.PluginBuilderActionMode
import top.wkbin.zaomeng.data.api.PluginBuilderSettingDraft
import top.wkbin.zaomeng.data.api.PluginBuilderTemplate
import top.wkbin.zaomeng.data.api.PluginDraft
import top.wkbin.zaomeng.data.api.PluginRuleActionDraft
import top.wkbin.zaomeng.data.api.PluginRuleActionType
import top.wkbin.zaomeng.data.api.PluginRuleDraft
import top.wkbin.zaomeng.data.api.PluginRuleEvent
import top.wkbin.zaomeng.data.api.PluginRuleMatchDraft
import top.wkbin.zaomeng.platform.base64Encode
import top.wkbin.zaomeng.platform.readZipEntries
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginBuilderServiceTest {
    private val builder = PluginBuilderService()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ai generation normalizes and validates model draft`() = kotlinx.coroutines.test.runTest {
        val service = PluginBuilderService {
            """{"name":"多语气回复","id":"ignored","version":"9.9.9","description":"生成三种回复","template":"chat_action","title":"多语气回复","prompt":"根据人物性格生成三条回复，参考草稿：{{seed_text}}","actionMode":"variants","settings":[]}"""
        }

        val result = service.generate("生成三种符合人物性格的回复")

        assertTrue(result.valid, result.issues.joinToString { it.message })
        assertEquals("0.1.0", result.draft.version)
        assertTrue(result.draft.id.startsWith("plugin-"))
        assertEquals(PluginBuilderActionMode.Variants, result.draft.actionMode)
    }

    @Test
    fun `ai generation retries malformed model output`() = kotlinx.coroutines.test.runTest {
        var calls = 0
        val service = PluginBuilderService {
            calls++
            if (calls == 1) {
                "not json"
            } else {
                """{"name":"场景约束","id":"","version":"0.1.0","description":"保持设定一致","template":"generation_enhancer","title":"场景约束","prompt":"保持人物性格和世界设定一致","actionMode":"suggest","settings":[]}"""
            }
        }

        assertTrue(service.generate("保持人物不出戏").valid)
        assertEquals(2, calls)
    }

    @Test
    fun `builder normalizes chinese name and derives explained permissions`() {
        val result = builder.validate(validChatDraft())

        assertTrue(result.valid, result.issues.joinToString { it.message })
        assertTrue(result.draft.id.startsWith("plugin-"))
        assertEquals(
            listOf("chat.context.read", "chat.draft.write", "model.invoke"),
            result.permissions.map { it.permission },
        )
        assertTrue(result.permissions.all { it.title.isNotBlank() && it.reason.isNotBlank() })
        assertEquals("2", result.manifest["apiVersion"]!!.jsonPrimitive.content)
        assertTrue(result.manifestJson.contains("{{config.tone}}"))
        assertEquals(
            "克制",
            result.manifest["settings"]!!.jsonArray.single().jsonObject["options"]!!.jsonArray
                .first().jsonObject["value"]!!.jsonPrimitive.content,
        )
        assertEquals("快捷接话-0.1.0.zaomeng-plugin.zip", result.filename)
    }

    @Test
    fun `all beginner templates produce executable declarative manifests`() {
        PluginBuilderTemplate.entries.forEach { template ->
            val result = builder.validate(
                validChatDraft().copy(
                    template = template,
                    prompt = if (template == PluginBuilderTemplate.ChatAction) {
                        "根据场景续写，语气为{{config.tone}}。草稿：{{seed_text}}"
                    } else {
                        "根据当前场景保持克制、自然，并推动剧情。"
                    },
                    settings = if (template == PluginBuilderTemplate.ChatAction) validChatDraft().settings else emptyList(),
                ),
            )
            assertTrue(result.valid, "$template: ${result.issues.joinToString { it.message }}")
            val evaluation = DeclarativePluginLoader.evaluate(result.draft.id, result.manifest)
            assertTrue(evaluation.executable, "$template: ${evaluation.capabilityNotice}")
        }
    }

    @Test
    fun `builder packages composable rules with derived permissions`() {
        val result = builder.validate(
            validChatDraft().copy(
                template = PluginBuilderTemplate.GenerationEnhancer,
                prompt = "保持人物行为自然。",
                settings = emptyList(),
                rules = listOf(
                    PluginRuleDraft(
                        title = "每五轮制造转折",
                        event = PluginRuleEvent.BeforeGeneration,
                        match = PluginRuleMatchDraft(everyTurns = 5, chancePercent = 30),
                        actions = listOf(
                            PluginRuleActionDraft(
                                type = PluginRuleActionType.AddInstruction,
                                instruction = "让一位神秘商人自然进入当前场景。",
                            ),
                        ),
                    ),
                    PluginRuleDraft(
                        title = "记录拒绝次数",
                        event = PluginRuleEvent.AfterTurn,
                        match = PluginRuleMatchDraft(keywords = listOf("拒绝")),
                        actions = listOf(
                            PluginRuleActionDraft(
                                type = PluginRuleActionType.IncrementState,
                                key = "refusals",
                                amount = 1,
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(result.valid, result.issues.joinToString { it.message })
        assertEquals(2, result.manifest["execution"]!!.jsonObject["rules"]!!.jsonArray.size)
        assertTrue("chat.state.write" in result.permissions.map { it.permission })
        assertTrue(DeclarativePluginLoader.evaluate(result.draft.id, result.manifest).rules.size == 2)
    }

    @Test
    fun `builder reports missing fields unknown variables and setting conflicts`() {
        val result = builder.validate(
            PluginDraft(
                name = "冲突插件",
                prompt = "使用{{config.missing}}和{{unknown}}",
                settings = listOf(
                    PluginBuilderSettingDraft(key = "tone", title = "语气", defaultValue = "温柔", options = listOf("克制")),
                    PluginBuilderSettingDraft(key = "tone", title = "另一语气", defaultValue = "直接", options = listOf("直接", "克制")),
                ),
            ),
        )

        assertFalse(result.valid)
        assertTrue(result.issues.any { it.field == "prompt" && it.message.contains("没有对应") })
        assertTrue(result.issues.any { it.field == "prompt" && it.message.contains("不支持变量") })
        assertTrue(result.issues.any { it.field.endsWith(".key") && it.message.contains("重复") })
        assertTrue(result.issues.any { it.field.endsWith(".options") })
    }

    @Test
    fun `packaged draft round trips through existing inspect and install flow`() {
        val result = builder.packagePlugin(validChatDraft())
        val entries = readZipEntries(result.bytes).associateBy { it.name }
        assertEquals(setOf("plugin.json", "README.md", "config.json"), entries.keys)
        assertTrue(entries["config.json"]!!.content.decodeToString().contains("克制"))

        val dir = createTempDirectory("plugin-builder-install")
        try {
            val storage = StorageService(dir.toString().toPath())
            val plugins = PluginService(storage)
            val operations = PluginOperationsService(storage, plugins)
            val inspection = operations.inspect(result.filename, base64Encode(result.bytes))
            json.decodeFromJsonElement<PluginPackageInspectionDto>(inspection)
            val token = inspection["token"]!!.jsonPrimitive.content
            assertTrue(inspection["compatible"]!!.jsonPrimitive.content.toBoolean())

            val installed = operations.install(token, confirmPermissions = true, allowUpdate = false)
            assertTrue(installed["executable"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("克制", plugins.getConfig(installed["id"]!!.jsonPrimitive.content)["tone"]!!.jsonPrimitive.content)
            assertEquals(
                installed["id"]!!.jsonPrimitive.content,
                plugins.list()["items"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content,
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun validChatDraft() = PluginDraft(
        name = "快捷接话",
        version = "0.1.0",
        description = "根据当前场景生成一条回复草稿。",
        title = "快捷接话",
        prompt = "根据当前场景续写，语气为{{config.tone}}。草稿：{{seed_text}}",
        actionMode = PluginBuilderActionMode.Suggest,
        settings = listOf(
            PluginBuilderSettingDraft(
                key = "tone",
                title = "语气",
                defaultValue = "克制",
                options = listOf("克制", "温柔", "直接"),
            ),
        ),
    )
}

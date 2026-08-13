package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.plugins.builtin.BuiltinPlugins
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 回归：首次启用任意插件时，默认开启的内置插件不能一起被关掉。 */
class PluginServiceTest {
    @Test
    fun `enabling inner thoughts keeps other default plugins enabled`() {
        val dir = createTempDirectory("zaomeng-plugin-repro")
        try {
            val service = PluginService(StorageService(dir.toString().toPath()), BuiltinPlugins.all)

            // 首次运行：默认插件开启、读心关闭
            val initial = itemsOf(service)
            assertEquals(false, enabledOf(initial, "com.zaomeng.inner-thoughts"))
            assertTrue(defaultEnabledIds(initial).all { enabledOf(initial, it) }, "默认插件初始应全部开启")

            // 启用读心后，其它默认插件必须保持开启
            service.setEnabled("com.zaomeng.inner-thoughts", true)
            val after = itemsOf(service)
            assertEquals(true, enabledOf(after, "com.zaomeng.inner-thoughts"))
            assertTrue(
                defaultEnabledIds(after).all { enabledOf(after, it) },
                "启用读心后默认插件不应被关闭",
            )

            // 关闭读心后同样保持其它插件状态
            service.setEnabled("com.zaomeng.inner-thoughts", false)
            val disabled = itemsOf(service)
            assertEquals(false, enabledOf(disabled, "com.zaomeng.inner-thoughts"))
            assertTrue(defaultEnabledIds(disabled).all { enabledOf(disabled, it) })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `disabling a default plugin keeps the rest enabled`() {
        val dir = createTempDirectory("zaomeng-plugin-repro-2")
        try {
            val service = PluginService(StorageService(dir.toString().toPath()), BuiltinPlugins.all)
            service.setEnabled("com.zaomeng.ai-association", false)
            val items = itemsOf(service)
            assertEquals(false, enabledOf(items, "com.zaomeng.ai-association"))
            assertTrue(
                defaultEnabledIds(items).filter { it != "com.zaomeng.ai-association" }
                    .all { enabledOf(items, it) },
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `broken state file missing defaults is repaired on read`() {
        val dir = createTempDirectory("zaomeng-plugin-repro-3")
        try {
            val storage = StorageService(dir.toString().toPath())
            val service = PluginService(storage, BuiltinPlugins.all)
            // 模拟旧版本写坏的 enabled.json：只有读心、默认插件全部丢失
            storage.writeTextAtomically(
                (dir.toString().toPath() / "plugins" / "enabled.json"),
                """{"enabled":["com.zaomeng.inner-thoughts"]}""",
            )
            val items = itemsOf(service)
            assertEquals(true, enabledOf(items, "com.zaomeng.inner-thoughts"))
            assertTrue(defaultEnabledIds(items).all { enabledOf(items, it) }, "损坏状态应自动修复默认插件")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `third party packages are stored but never executable`() {
        val dir = createTempDirectory("zaomeng-third-party-plugin")
        try {
            val storage = StorageService(dir.toString().toPath())
            val pluginDir = dir.toString().toPath() / "plugins" / "sample-plugin"
            storage.mkdirs(pluginDir)
            storage.writeTextAtomically(
                pluginDir / "plugin.json",
                """{"id":"sample-plugin","name":"示例插件","version":"1.0.0","contributes":{"chatActions":[{"id":"act","title":"动作"}]}}""",
            )
            storage.writeTextAtomically(
                dir.toString().toPath() / "plugins" / "enabled.json",
                """{"enabled":["sample-plugin"]}""",
            )
            val service = PluginService(storage, BuiltinPlugins.all)

            val item = itemsOf(service).first {
                it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == "sample-plugin"
            }.jsonObject
            assertFalse(item["enabled"]!!.jsonPrimitive.content.toBoolean())
            assertFalse(item["executable"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("stored", item["status"]!!.jsonPrimitive.content)
            assertEquals("unsupported", item["executionMode"]!!.jsonPrimitive.content)

            assertFailsWith<IllegalArgumentException> {
                service.setEnabled("sample-plugin", true)
            }
            val disabled = service.setEnabled("sample-plugin", false)
            assertEquals("stored", disabled["status"]!!.jsonPrimitive.content)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `declarative third party package can be enabled and listed as executable`() {
        val dir = createTempDirectory("zaomeng-declarative-plugin")
        try {
            val storage = StorageService(dir.toString().toPath())
            val pluginDir = dir.toString().toPath() / "plugins" / "declarative-plugin"
            storage.mkdirs(pluginDir)
            storage.writeTextAtomically(
                pluginDir / "plugin.json",
                """
                {
                  "id":"declarative-plugin",
                  "name":"声明式插件",
                  "version":"1.0.0",
                  "permissions":["chat.context.read","chat.draft.write","generation.enhance","model.invoke"],
                  "contributes":{
                    "chatActions":[{"id":"act","title":"动作"}],
                    "generationEnhancers":[{"id":"inner-voice","title":"内心声音"}]
                  },
                  "execution":{
                    "mode":"declarative",
                    "chatActions":{"act":{"operation":"suggest","direction":"生成草稿：{{seed_text}}"}},
                    "generationEnhancers":{"inner-voice":{"rule":"为每个发言角色增加一句简短内心独白"}}
                  }
                }
                """.trimIndent(),
            )
            val service = PluginService(storage, BuiltinPlugins.all)

            val enabled = service.setEnabled("declarative-plugin", true)

            assertEquals(true, enabled["enabled"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(true, enabled["executable"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("declarative-kotlin", enabled["executionMode"]!!.jsonPrimitive.content)
            assertEquals("enabled", enabled["status"]!!.jsonPrimitive.content)
            assertEquals(
                "为每个发言角色增加一句简短内心独白",
                service.generationEnhancerRule("declarative-plugin", "inner-voice"),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `disabling plugin removes active enhancer directives from sessions`() {
        val dir = createTempDirectory("zaomeng-plugin-enhancer-disable")
        try {
            val storage = StorageService(dir.toString().toPath())
            val pluginDir = dir.toString().toPath() / "plugins" / "enhancer-plugin"
            storage.mkdirs(pluginDir)
            storage.writeTextAtomically(
                pluginDir / "plugin.json",
                """
                {
                  "id":"enhancer-plugin",
                  "name":"增强插件",
                  "apiVersion":"1",
                  "permissions":["chat.context.read","generation.enhance","model.invoke"],
                  "contributes":{"generationEnhancers":[{"id":"rule","title":"规则"}]},
                  "execution":{"mode":"declarative","generationEnhancers":{"rule":{"rule":"增强规则"}}}
                }
                """.trimIndent(),
            )
            storage.writeRunManifest("run-1", kotlinx.serialization.json.buildJsonObject { put("id", "run-1") })
            val sessionFile = storage.getDialogueSessionManifestFile("run-1", "session-1")
            storage.mkdirs(sessionFile.parent!!)
            storage.writeTextAtomically(
                sessionFile,
                """{"id":"session-1","plugin_enhancer_states":{"enhancer-plugin":{"rule":true}},"plugin_enhancer_directives":{"enhancer-plugin/rule":"增强规则","other/rule":"保留"}}""",
            )
            val service = PluginService(storage, BuiltinPlugins.all)
            service.setEnabled("enhancer-plugin", true)

            service.setEnabled("enhancer-plugin", false)

            val session = Json.parseToJsonElement(storage.readText(sessionFile)).jsonObject
            assertFalse("enhancer-plugin" in session["plugin_enhancer_states"]!!.jsonObject)
            assertFalse("enhancer-plugin/rule" in session["plugin_enhancer_directives"]!!.jsonObject)
            assertTrue("other/rule" in session["plugin_enhancer_directives"]!!.jsonObject)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun itemsOf(service: PluginService) =
        service.list()["items"]!!.jsonArray

    private fun enabledOf(items: kotlinx.serialization.json.JsonArray, id: String): Boolean =
        items.first { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == id }
            .jsonObject["enabled"]?.jsonPrimitive?.contentOrNull == "true"

    private fun defaultEnabledIds(items: kotlinx.serialization.json.JsonArray): Set<String> =
        items.filter { it.jsonObject["defaultEnabled"]?.jsonPrimitive?.contentOrNull == "true" }
            .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            .toSet()
}

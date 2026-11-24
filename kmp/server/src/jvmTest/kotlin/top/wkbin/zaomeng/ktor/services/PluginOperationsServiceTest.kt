package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PluginOperationsServiceTest {
    @Test
    fun `install requires explicit permission confirmation`() {
        withPluginStorage("plugin-confirm") { storage ->
            stageManifest(storage, "confirmtoken", "confirm-plugin", version = "1.0.0")
            val service = PluginService(storage)
            val operations = PluginOperationsService(storage, service)

            assertFailsWith<IllegalArgumentException> {
                operations.install("confirmtoken", confirmPermissions = false, allowUpdate = false)
            }
            assertFalse(storage.exists(storage.getStorageRoot() / "plugins/confirm-plugin"))
        }
    }

    @Test
    fun `update preserves config data and logs`() {
        withPluginStorage("plugin-update") { storage ->
            val installed = storage.getStorageRoot() / "plugins/update-plugin"
            val service = PluginService(storage)
            storage.mkdirs(installed / "data")
            storage.writeTextAtomically(installed / "plugin.json", manifest("update-plugin", "1.0.0"))
            storage.writeTextAtomically(installed / "config.json", """{"tone":"warm"}""")
            service.compareAndSetData("update-plugin", "notes", 0, "kept")
            storage.writeTextAtomically(installed / "plugin-logs.jsonl", "old-log\n")
            stageManifest(storage, "updatetoken", "update-plugin", version = "2.0.0")
            val operations = PluginOperationsService(storage, service)
            service.appendLog("update-plugin", "info", "new-log")

            val result = operations.install("updatetoken", confirmPermissions = true, allowUpdate = true)

            assertEquals("2.0.0", result["version"]!!.jsonPrimitive.content)
            assertEquals("kept", service.readData("update-plugin", "notes"))
            assertEquals(1, service.readDataSnapshot("update-plugin", "notes").revision)
            assertEquals("old-log\n", storage.readText(installed / "plugin-logs.jsonl"))
            assertEquals("new-log", service.logs("update-plugin").getValue("items").jsonArray.last()
                .jsonObject.getValue("message").jsonPrimitive.content)
            assertEquals("""{"tone":"warm"}""", storage.readText(installed / "config.json"))
        }
    }

    @Test
    fun `disabled plugin action is rejected by service boundary`() = runBlocking {
        withPluginStorage("plugin-disabled") { storage ->
            val installed = storage.getStorageRoot() / "plugins/disabled-plugin"
            storage.mkdirs(installed)
            storage.writeTextAtomically(installed / "plugin.json", manifest("disabled-plugin", "1.0.0"))
            val operations = PluginOperationsService(storage, PluginService(storage))

            val error = assertFailsWith<IllegalArgumentException> {
                operations.invokeChatAction("run", "session", "disabled-plugin", "act", "", "")
            }
            assertEquals("插件「disabled-plugin」未启用。", error.message)
        }
    }

    @Test
    fun `undeclared action is rejected before host execution`() = runBlocking {
        withPluginStorage("plugin-action") { storage ->
            val installed = storage.getStorageRoot() / "plugins/action-plugin"
            storage.mkdirs(installed)
            storage.writeTextAtomically(installed / "plugin.json", manifest("action-plugin", "1.0.0"))
            val service = PluginService(storage)
            service.setEnabled("action-plugin", true)
            val operations = PluginOperationsService(storage, service)

            val error = assertFailsWith<IllegalArgumentException> {
                operations.invokeChatAction("run", "session", "action-plugin", "missing", "", "")
            }
            assertEquals("插件「action-plugin」未声明聊天动作「missing」。", error.message)
        }
    }

    @Test
    fun `update rejects package managed revision records`() {
        withPluginStorage("plugin-update-versions") { storage ->
            val installed = storage.getStorageRoot() / "plugins/update-plugin"
            storage.mkdirs(installed / "data")
            storage.writeTextAtomically(installed / "plugin.json", manifest("update-plugin", "1.0.0"))
            storage.writeTextAtomically(installed / "data/notes.txt", "old")
            stageManifest(storage, "versiontoken", "update-plugin", version = "2.0.0")
            val stagedVersions = storage.getStorageRoot() / "plugin-staging/versiontoken/data/.versions"
            storage.mkdirs(stagedVersions)
            storage.writeTextAtomically(
                stagedVersions / "notes.json",
                """{"format":"zaomeng-plugin-data-v1","revision":7,"value":"package"}""",
            )
            val service = PluginService(storage)
            val operations = PluginOperationsService(storage, service)

            assertFailsWith<IllegalArgumentException> {
                operations.install("versiontoken", confirmPermissions = true, allowUpdate = true)
            }
            assertEquals("old", service.readData("update-plugin", "notes"))
            assertEquals(0, service.readDataSnapshot("update-plugin", "notes").revision)
        }
    }

    @Test
    fun `enhancer update preserves bounded history revision and other plugin state`() {
        withPluginStorage("plugin-enhancer-latest") { storage ->
            val pluginDirectory = storage.getStorageRoot() / "plugins/enhancer-plugin"
            storage.mkdirs(pluginDirectory)
            storage.writeTextAtomically(
                pluginDirectory / "plugin.json",
                """
                {
                  "id":"enhancer-plugin","name":"Enhancer","version":"1.0.0","apiVersion":"1",
                  "permissions":["chat.context.read","generation.enhance","model.invoke"],
                  "contributes":{"generationEnhancers":[{"id":"rule","title":"Rule"}]},
                  "execution":{"mode":"declarative","generationEnhancers":{"rule":{"rule":"new directive"}}}
                }
                """.trimIndent(),
            )
            storage.writeTextAtomically(
                storage.getStorageRoot() / "plugins/enabled.json",
                """{"enabled":["enhancer-plugin"]}""",
            )
            val sessionFile = storage.getDialogueSessionManifestFile("run-1", "session-1")
            storage.mkdirs(sessionFile.parent!!)
            storage.writeTextAtomically(
                sessionFile,
                """
                {
                  "run_id":"run-1","session_id":"session-1","revision":7,
                  "transcript_start":5,"transcript_count":6,
                  "transcript":[{"seq":5,"speaker":"林晚","message":"latest"}],
                  "plugin_enhancer_states":{"other":{"rule":true}},
                  "plugin_enhancer_directives":{"other/rule":"keep"},
                  "plugin_rule_states":{"other":{"count":"2"}}
                }
                """.trimIndent(),
            )
            val operations = PluginOperationsService(storage, PluginService(storage))

            val updated = operations.setEnhancerState("run-1", "session-1", "enhancer-plugin", "rule", true)

            assertEquals("7", updated["revision"]!!.jsonPrimitive.content)
            assertEquals("5", updated["transcript_start"]!!.jsonPrimitive.content)
            assertEquals("6", updated["transcript_count"]!!.jsonPrimitive.content)
            assertEquals("latest", updated["transcript"]!!.jsonArray.single().jsonObject["message"]!!.jsonPrimitive.content)
            assertEquals("true", updated["plugin_enhancer_states"]!!.jsonObject["other"]!!.jsonObject["rule"]!!.jsonPrimitive.content)
            assertEquals("true", updated["plugin_enhancer_states"]!!.jsonObject["enhancer-plugin"]!!.jsonObject["rule"]!!.jsonPrimitive.content)
            assertEquals("keep", updated["plugin_enhancer_directives"]!!.jsonObject["other/rule"]!!.jsonPrimitive.content)
            assertEquals("new directive", updated["plugin_enhancer_directives"]!!.jsonObject["enhancer-plugin/rule"]!!.jsonPrimitive.content)
            assertEquals("2", updated["plugin_rule_states"]!!.jsonObject["other"]!!.jsonObject["count"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `plugin network URL rejects local and insecure targets`() {
        assertFailsWith<IllegalArgumentException> { validatePluginHttpUrl("http://example.com/api") }
        assertFailsWith<IllegalArgumentException> { validatePluginHttpUrl("https://localhost/api") }
        assertFailsWith<IllegalArgumentException> { validatePluginHttpUrl("https://127.0.0.1/api") }
        assertFailsWith<IllegalArgumentException> { validatePluginHttpUrl("https://127.1/api") }
        assertFailsWith<IllegalArgumentException> { validatePluginHttpUrl("https://2130706433/api") }
        assertFailsWith<IllegalArgumentException> { validatePluginHttpUrl("https://169.254.169.254/latest/meta-data") }
        assertEquals("https://example.com/api", validatePluginHttpUrl("https://example.com/api"))
        assertFailsWith<IllegalArgumentException> {
            sanitizePluginHttpHeaders(mapOf("Host" to "internal"))
        }
    }

    private fun stageManifest(storage: StorageService, token: String, id: String, version: String) {
        val staging = storage.getStorageRoot() / "plugin-staging/$token"
        storage.mkdirs(staging)
        storage.writeTextAtomically(staging / "plugin.json", manifest(id, version))
    }

    private fun manifest(id: String, version: String): String =
        """
        {
          "id":"$id",
          "name":"Test plugin",
          "version":"$version",
          "apiVersion":"1",
          "permissions":["chat.context.read","chat.draft.write","model.invoke"],
          "contributes":{"chatActions":[{"id":"act","title":"Action"}]},
          "execution":{"mode":"declarative","chatActions":{"act":{"operation":"suggest","direction":"Continue"}}}
        }
        """.trimIndent()

    private inline fun withPluginStorage(prefix: String, block: (StorageService) -> Unit) {
        val dir = createTempDirectory(prefix)
        try {
            block(StorageService(dir.toString().toPath()))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

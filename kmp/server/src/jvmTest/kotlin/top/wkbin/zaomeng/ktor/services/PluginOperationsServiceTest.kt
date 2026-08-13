package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.runBlocking
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
            storage.mkdirs(installed / "data")
            storage.writeTextAtomically(installed / "plugin.json", manifest("update-plugin", "1.0.0"))
            storage.writeTextAtomically(installed / "config.json", """{"tone":"warm"}""")
            storage.writeTextAtomically(installed / "data/notes.txt", "kept")
            storage.writeTextAtomically(installed / "plugin-logs.jsonl", "old-log\n")
            stageManifest(storage, "updatetoken", "update-plugin", version = "2.0.0")
            val service = PluginService(storage)
            val operations = PluginOperationsService(storage, service)

            val result = operations.install("updatetoken", confirmPermissions = true, allowUpdate = true)

            assertEquals("2.0.0", result["version"]!!.jsonPrimitive.content)
            assertEquals("kept", storage.readText(installed / "data/notes.txt"))
            assertEquals("old-log\n", storage.readText(installed / "plugin-logs.jsonl"))
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

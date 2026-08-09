package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.plugins.builtin.BuiltinPlugins
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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

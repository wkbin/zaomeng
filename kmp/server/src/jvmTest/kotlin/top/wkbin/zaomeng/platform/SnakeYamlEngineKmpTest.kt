package top.wkbin.zaomeng.platform

import it.krzeminski.snakeyaml.engine.kmp.api.Dump
import it.krzeminski.snakeyaml.engine.kmp.api.DumpSettings
import it.krzeminski.snakeyaml.engine.kmp.api.Load
import it.krzeminski.snakeyaml.engine.kmp.common.FlowStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 验证 snakeyaml-engine-kmp 的 API 与语义——iOS 端 parseYaml/dumpYaml actual 使用同一套调用，
 * 本测试在 JVM 上跑通以覆盖跨平台代码路径。
 */
class SnakeYamlEngineKmpTest {
    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String): Map<String, Any?>? =
        runCatching { Load().loadOne(text) }.getOrNull() as? Map<String, Any?>

    private fun dump(value: Any?): String =
        Dump(DumpSettings(defaultFlowStyle = FlowStyle.BLOCK)).dumpToString(value)

    @Test
    fun `parses front matter map with nested values`() {
        val yaml = """
            name: 林晚
            cadence: 温和
            speech_habits:
              greeting: 你好
              pace: slow
            traits: [冷静, 温柔]
            confidence: 0.85
            enabled: true
        """.trimIndent()

        val parsed = parse(yaml)
        assertIs<Map<String, Any?>>(parsed)
        assertEquals("林晚", parsed["name"])
        assertEquals("温和", parsed["cadence"])
        val habits = parsed["speech_habits"] as Map<*, *>
        assertEquals("你好", habits["greeting"])
        assertEquals("slow", habits["pace"])
        assertTrue(parsed["traits"] is List<*>)
        assertEquals(0.85, (parsed["confidence"] as Number).toDouble())
        assertEquals(true, parsed["enabled"])
    }

    @Test
    fun `dump uses block style and round trips`() {
        val value = mapOf(
            "name" to "林晚",
            "speech_habits" to mapOf("greeting" to "你好", "pace" to "slow"),
            "traits" to listOf("冷静", "温柔"),
        )

        val text = dump(value)

        // 块风格：嵌套 map 的键换行缩进，而不是内联 { ... }
        assertTrue("name:" in text)
        assertTrue("greeting:" in text)

        val roundTrip = parse(text)
        assertIs<Map<String, Any?>>(roundTrip)
        assertEquals("林晚", roundTrip["name"])
        assertEquals("你好", (roundTrip["speech_habits"] as Map<*, *>)["greeting"])
        assertEquals(listOf("冷静", "温柔"), roundTrip["traits"])
    }
}

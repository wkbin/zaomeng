package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals

/** 群聊兜底：同一角色连续多条时合并，避免同一人连着发消息。 */
class DialogueResponseParserTest {
    @Test
    fun `consecutive same speaker messages are merged`() {
        val content = """
            [{"speaker":"林七夜","message":"第一句。"},
             {"speaker":"林七夜","message":"第二句。"},
             {"speaker":"沈青竹","message":"接话。"}]
        """.trimIndent()
        val parsed = DialogueResponseParser.parse(
            content,
            allowedSpeakers = listOf("林七夜", "沈青竹"),
        )
        assertEquals(2, parsed.size)
        assertEquals("林七夜", parsed[0].speaker)
        assertEquals("第一句。\n第二句。", parsed[0].message)
        assertEquals("沈青竹", parsed[1].speaker)
    }

    @Test
    fun `non consecutive same speaker is preserved`() {
        val content = """
            [{"speaker":"林七夜","message":"第一句。"},
             {"speaker":"沈青竹","message":"接话。"},
             {"speaker":"林七夜","message":"回应。"}]
        """.trimIndent()
        val parsed = DialogueResponseParser.parse(
            content,
            allowedSpeakers = listOf("林七夜", "沈青竹"),
        )
        assertEquals(listOf("林七夜", "沈青竹", "林七夜"), parsed.map { it.speaker })
    }
}

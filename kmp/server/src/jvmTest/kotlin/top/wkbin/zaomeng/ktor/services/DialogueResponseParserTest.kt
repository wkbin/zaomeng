package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Consecutive alternatives from the same speaker must not become one oversized response. */
class DialogueResponseParserTest {
    @Test
    fun `consecutive same speaker messages keep only the first response`() {
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
        assertEquals("第一句。", parsed[0].message)
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

    @Test
    fun `responses object contract is parsed`() {
        val parsed = DialogueResponseParser.parse(
            """{"responses":[{"speaker":"潘金莲","message":"公子请讲。"}]}""",
            allowedSpeakers = listOf("潘金莲"),
        )

        assertEquals("潘金莲", parsed.single().speaker)
        assertEquals("公子请讲。", parsed.single().message)
    }

    @Test
    fun `ndjson dialogue contract is parsed`() {
        val parsed = DialogueResponseParser.parse(
            """{"speaker":"潘金莲","message":"第一句。"}
{"speaker":"旁白","message":"帘外风起。"}
""",
            allowedSpeakers = listOf("潘金莲", "旁白"),
        )

        assertEquals(listOf("潘金莲", "旁白"), parsed.map { it.speaker })
        assertEquals("帘外风起。", parsed.last().message)
    }

    @Test
    fun `ndjson keeps completed lines when final line is truncated`() {
        val parsed = DialogueResponseParser.parse(
            """{"speaker":"潘金莲","message":"完整回复。"}
{"speaker":"旁白","message":"未完成"""",
            allowedSpeakers = listOf("潘金莲", "旁白"),
        )

        assertEquals("潘金莲", parsed.single().speaker)
        assertEquals("完整回复。", parsed.single().message)
    }

    @Test
    fun `plain text is assigned when exactly one character can reply`() {
        val parsed = DialogueResponseParser.parseSingleSpeakerPlainText(
            "（掩唇一笑）公子这话问得好生直白。",
            allowedSpeakers = listOf("潘金莲", "旁白", "场景提示"),
            forbiddenSpeakers = listOf("沈砚舟"),
        )

        assertEquals("潘金莲", parsed?.single()?.speaker)
        assertEquals("（掩唇一笑）公子这话问得好生直白。", parsed?.single()?.message)
    }

    @Test
    fun `plain text is not guessed in a multi character scene`() {
        val parsed = DialogueResponseParser.parseSingleSpeakerPlainText(
            "有人接过了话。",
            allowedSpeakers = listOf("林七夜", "沈青竹", "旁白"),
        )

        assertNull(parsed)
    }

    @Test
    fun `json shaped output never uses plain text fallback`() {
        val parsed = DialogueResponseParser.parseSingleSpeakerPlainText(
            "[{\"speaker\":\"潘金莲\",\"message\":\"未完成\"}",
            allowedSpeakers = listOf("潘金莲"),
        )

        assertNull(parsed)
    }
}

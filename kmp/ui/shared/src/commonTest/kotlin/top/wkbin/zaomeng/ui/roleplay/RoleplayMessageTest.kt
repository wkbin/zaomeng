package top.wkbin.zaomeng.ui.roleplay

import kotlin.test.Test
import kotlin.test.assertEquals

class RoleplayMessageTest {
    @Test
    fun escapedStatusMarkupIsSeparatedFromProseInOrder() {
        val fence = "\u0060".repeat(3)
        val text = "守卫推开门。\\\n\\\n“请进。”\n" +
            "\\<StatusBlocks>\\\n\\<details>\\<summary>巡逻状态\\</summary>\\\n" +
            "$fence\\\n地点：北门\\\n时间：清晨\\\n$fence\\\n\\</details>\\\n" +
            "<details><summary>任务</summary>开门</details></StatusBlocks>\n风吹过院子。"
        assertEquals(
            listOf(
                RoleplayBlock.Prose("守卫推开门。\n\n“请进。”"),
                RoleplayBlock.Status("巡逻状态", "地点：北门\n时间：清晨"),
                RoleplayBlock.Status("任务", "开门"),
                RoleplayBlock.Prose("风吹过院子。"),
            ),
            parseRoleplayMessage(text),
        )
    }

    @Test
    fun streamingDoesNotExposeAnUnfinishedStatusBlockAsDialogue() {
        val text = "“请进。”<StatusBlocks><details><summary>状态</summary>北门</details>"
        assertEquals(listOf(RoleplayBlock.Prose("“请进。”")), parseRoleplayMessage(text, streaming = true))
        assertEquals(
            listOf(RoleplayBlock.Prose("“请进。”"), RoleplayBlock.Status("状态", "北门")),
            parseRoleplayMessage(text),
        )
    }

    @Test
    fun quotedSpeechAndMarkedActionsHaveDifferentSpans() {
        assertEquals(
            listOf(
                RoleplaySpan("守卫抬手。", RoleplayTone.Narration),
                RoleplaySpan("“请进。”", RoleplayTone.Dialogue),
                RoleplaySpan("\n", RoleplayTone.Narration),
                RoleplaySpan("门缓缓打开", RoleplayTone.Narration),
            ),
            roleplaySpans("守卫抬手。“请进。”\n*门缓缓打开*"),
        )
        assertEquals(listOf(RoleplaySpan("早上好。", RoleplayTone.Plain)), roleplaySpans("早上好。"))
    }

    @Test
    fun readingParagraphsKeepSingleLineBreaksAndSplitBlankLines() {
        assertEquals(
            listOf("北门。\r\n雾未散。", "她说：\"请进。\"", "院子很安静。"),
            roleplayParagraphs("北门。\r\n雾未散。\r\n \r\n她说：\"请进。\"\n\n\n院子很安静。"),
        )
        assertEquals(
            listOf(
                RoleplaySpan("她说：", RoleplayTone.Narration),
                RoleplaySpan("\"请进。\"", RoleplayTone.Dialogue),
            ),
            roleplaySpans("她说：\"请进。\""),
        )
    }
}

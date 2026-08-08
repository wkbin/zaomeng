package top.wkbin.zaomeng.feature.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatMentionTest {
    @Test
    fun `inserting a mention places the cursor after the mention`() {
        val result = TextFieldValue("你好", TextRange(2)).insertMention("林黛玉")

        assertEquals("你好 @林黛玉 ", result.text)
        assertEquals(result.text.length, result.selection.start)
    }

    @Test
    fun `backspace after a mention removes the complete mention`() {
        val previous = TextFieldValue("请回答 @林黛玉 ", TextRange(9))
        val next = TextFieldValue("请回答 @林黛玉", TextRange(8))

        val result = normalizeMentionDeletion(
            previous = previous,
            next = next,
            participants = listOf("林黛玉"),
        )

        assertEquals("请回答 ", result.text)
        assertEquals(4, result.selection.start)
    }

    @Test
    fun `deleting part of a mention removes the complete mention`() {
        val previous = TextFieldValue("请 @林黛玉 回答", TextRange(3, 5))
        val next = TextFieldValue("请 @玉 回答", TextRange(3))

        val result = normalizeMentionDeletion(
            previous = previous,
            next = next,
            participants = listOf("林黛玉"),
        )

        assertEquals("请 回答", result.text)
        assertEquals(2, result.selection.start)
    }
}

package top.wkbin.zaomeng.feature.chat.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmotionIndicatorTest {
    @Test
    fun `classifies supported emotions locally`() {
        assertEquals("😄", classifyEmotion("她忍不住开心地笑了"))
        assertEquals("😠", classifyEmotion("压住心里的愤怒"))
        assertEquals("😰", classifyEmotion("他感到有些紧张"))
        assertEquals("😌", classifyEmotion("心情终于平静下来"))
    }

    @Test
    fun `returns null for blank or unmatched text`() {
        assertNull(classifyEmotion(""))
        assertNull(classifyEmotion("他想起了昨日的约定"))
    }
}

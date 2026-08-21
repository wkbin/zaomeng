package top.wkbin.zaomeng.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformTtsTest {

    @Test
    fun cleanSpokenText_stripsFullWidthAndHalfWidthParentheses() {
        val input = "（微笑着递过茶盏）姑娘请用茶，（低声自语：今日天气倒好）这茶可是今年新采的。"
        val expected = "姑娘请用茶，这茶可是今年新采的。"
        assertEquals(expected, cleanSpokenText(input))
    }

    @Test
    fun cleanSpokenText_stripsBracketsAndCues() {
        val input = "[冷笑一声] 凭你也配？【拔剑出鞘】纳命来！"
        val expected = "凭你也配？纳命来！"
        assertEquals(expected, cleanSpokenText(input))
    }

    @Test
    fun cleanSpokenText_returnsPureSpokenUtterance() {
        val input = "林妹妹，你今日身子可大好了？"
        assertEquals("林妹妹，你今日身子可大好了？", cleanSpokenText(input))
    }

    @Test
    fun cleanSpokenText_handlesBlankOrPureAction() {
        assertEquals("", cleanSpokenText(""))
        assertEquals("（默默流泪）", cleanSpokenText("（默默流泪）"))
    }
}

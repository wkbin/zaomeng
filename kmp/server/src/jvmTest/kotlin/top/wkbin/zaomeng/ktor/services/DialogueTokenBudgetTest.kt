package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals

class DialogueTokenBudgetTest {
    @Test
    fun `reasoning off uses compact dialogue budget`() {
        assertEquals(1200, DialogueService.resolveDialogueMaxTokens(0, "off"))
        assertEquals(1240, DialogueService.resolveDialogueMaxTokens(2, "off"))
        assertEquals(1600, DialogueService.resolveDialogueMaxTokens(3, "OFF"))
    }

    @Test
    fun `reasoning modes retain reasoning budget`() {
        assertEquals(8192, DialogueService.resolveDialogueMaxTokens(2, "low"))
        assertEquals(8192, DialogueService.resolveDialogueMaxTokens(2, "auto"))
    }
}

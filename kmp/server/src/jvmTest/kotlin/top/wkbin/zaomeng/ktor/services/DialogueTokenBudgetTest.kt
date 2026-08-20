package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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

    @Test
    fun `pacing scales token budget within dialogue ceiling`() {
        assertEquals(600, DialogueService.resolveDialogueMaxTokens(0, "off", "brief"))
        assertEquals(1800, DialogueService.resolveDialogueMaxTokens(0, "off", "detailed"))
        assertEquals(1200, DialogueService.resolveDialogueMaxTokens(0, "off", "unexpected"))
        assertEquals(12288, DialogueService.resolveDialogueMaxTokens(0, "auto", "detailed"))
        assertEquals(16000, DialogueService.resolveDialogueMaxTokens(40, "auto", "detailed"))
    }

    @Test
    fun `pacing adds an instruction only for non-normal modes`() {
        val messages = listOf(LlmClient.ChatMessage(role = "user", content = "hello"))
        assertSame(messages, DialogueService.applyPacingInstruction(messages, "normal"))
        assertEquals("system", DialogueService.applyPacingInstruction(messages, "brief").last().role)
        assertEquals(true, DialogueService.applyPacingInstruction(messages, "detailed").last().content?.contains("细腻描写"))
    }
}

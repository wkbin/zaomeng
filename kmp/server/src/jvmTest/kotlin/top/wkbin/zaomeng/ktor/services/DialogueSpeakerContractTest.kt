package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialogueSpeakerContractTest {
    @Test
    fun `active and eligible responders never restore user controlled character`() {
        val active = selectDialogueActiveParticipants(
            participants = listOf("李斌", "严晓莉"),
            presentParticipants = listOf("李斌"),
            mode = "act",
            inputSpeaker = "李斌",
            controlledCharacter = "李斌",
        )
        val eligible = eligibleDialogueResponders(
            activeParticipants = listOf("李斌") + active,
            mode = "act",
            inputSpeaker = "李斌",
            controlledCharacter = "李斌",
        )

        assertEquals(listOf("严晓莉"), active)
        assertEquals(listOf("严晓莉"), eligible)
    }

    @Test
    fun `responder hints never grant the controlled character permission to speak`() {
        listOf("user_input", "plot", "narration").forEach { kind ->
            val hints = DialoguePromptRules.responderHints(
                mode = "act",
                participants = listOf("李斌", "严晓莉"),
                speaker = "李斌",
                messageKind = kind,
                controlledCharacter = "李斌",
            )

            assertEquals(listOf("严晓莉"), hints.map { it["name"] }, "message kind: $kind")
        }
    }

    @Test
    fun `scene rules contain no permission for controlled character to react`() {
        val modeRule = DialoguePromptRules.modeRule("act", "plot", "李斌")
        val styleRule = DialoguePromptRules.responseStyleRule("act", "plot", "李斌")
        val hostBrief = DialoguePromptRules.hostPromptBrief(
            mode = "act",
            speaker = "李斌",
            participants = listOf("李斌", "严晓莉"),
            messageKind = "plot",
            controlledCharacter = "李斌",
        )

        listOf(modeRule, styleRule, hostBrief).forEach { rule ->
            assertTrue("never" in rule.lowercase())
            assertFalse("may react" in rule.lowercase())
        }
    }

    @Test
    fun `fourth wall rules allow author negotiation while keeping speaker contract`() {
        val modeRule = DialoguePromptRules.modeRule("observe", "fourth_wall")
        val speakerRule = DialoguePromptRules.speakerRule("observe", emptyMap(), "fourth_wall")
        val styleRule = DialoguePromptRules.responseStyleRule("observe", "fourth_wall")

        assertTrue("author" in modeRule.lowercase())
        assertTrue("resist" in modeRule.lowercase() || "refuse" in modeRule.lowercase())
        assertTrue("author" in speakerRule.lowercase())
        assertTrue("refuse" in styleRule.lowercase())
        assertEquals("fourth_wall", DialoguePromptRules.normalizeMessageKind("author"))
    }
}

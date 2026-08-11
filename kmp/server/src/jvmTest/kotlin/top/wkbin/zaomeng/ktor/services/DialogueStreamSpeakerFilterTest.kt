package top.wkbin.zaomeng.ktor.services

import top.wkbin.zaomeng.ktor.utils.StreamEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class DialogueStreamSpeakerFilterTest {
    @Test
    fun `controlled character is never exposed as a temporary streaming reply`() {
        val visible = filterDialogueStreamEvents(
            events = listOf(
                event(index = 0, speaker = "严晓莉", text = "六百六十六？"),
                event(index = 1, speaker = "李斌", text = "我来回答"),
            ),
            allowedSpeakers = listOf("李斌", "严晓莉", "旁白", "场景提示"),
            forbiddenSpeakers = listOf("李斌", "李斌"),
        )

        assertEquals(listOf("严晓莉"), visible.map(StreamEvent::speaker))
        assertEquals(listOf(0), visible.map(StreamEvent::index))
    }

    @Test
    fun `speaker names use the same whitespace normalization as final parsing`() {
        val visible = filterDialogueStreamEvents(
            events = listOf(event(index = 0, speaker = "严 晓 莉", text = "回答")),
            allowedSpeakers = listOf("严晓莉"),
            forbiddenSpeakers = emptyList(),
        )

        assertEquals("严晓莉", visible.single().speaker)
    }

    private fun event(index: Int, speaker: String, text: String) = StreamEvent(
        index = index,
        speaker = speaker,
        role = "assistant",
        field = "message",
        text = text,
    )
}

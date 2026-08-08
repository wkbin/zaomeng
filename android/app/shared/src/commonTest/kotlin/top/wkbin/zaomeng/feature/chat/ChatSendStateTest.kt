package top.wkbin.zaomeng.feature.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.TranscriptItemDto

class ChatSendStateTest {
    private val baseline = listOf(
        TranscriptItemDto(speaker = "You", message = "Earlier", role = "user"),
    )

    @Test
    fun userMessageAloneDoesNotCompleteAStreamingReply() {
        val transcript = baseline + TranscriptItemDto(
            speaker = "You",
            message = "New message",
            role = "user",
        )

        assertFalse(hasCommittedReply(baseline, transcript))
    }

    @Test
    fun characterReplyCompletesAStreamingReply() {
        val transcript = baseline + listOf(
            TranscriptItemDto(speaker = "You", message = "New message", role = "user"),
            TranscriptItemDto(speaker = "Lin", message = "Reply", role = "character"),
        )

        assertTrue(hasCommittedReply(baseline, transcript))
    }
}

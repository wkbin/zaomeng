package top.wkbin.zaomeng.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import top.wkbin.zaomeng.data.api.TranscriptItemDto

class ChatSendStateTest {
    private val baseline = listOf(
        TranscriptItemDto(speaker = "You", message = "Earlier", role = "user"),
    )

    @Test
    fun userMessageAloneDoesNotCountAsCommittedAppend() {
        val transcript = baseline + TranscriptItemDto(
            speaker = "You",
            message = "New message",
            role = "user",
        )

        assertEquals(emptyList<TranscriptItemDto>(), committedAppend(baseline.size, transcript))
    }

    @Test
    fun characterReplyIsReturnedAsCommittedAppend() {
        val appended = listOf(
            TranscriptItemDto(speaker = "You", message = "New message", role = "user"),
            TranscriptItemDto(speaker = "Lin", message = "Reply", role = "character"),
        )
        val transcript = baseline + appended

        assertEquals(appended, committedAppend(baseline.size, transcript))
    }

    @Test
    fun unchangedTranscriptHasNoCommittedAppend() {
        assertEquals(emptyList<TranscriptItemDto>(), committedAppend(baseline.size, baseline))
    }

}

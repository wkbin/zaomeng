package top.wkbin.zaomeng.feature.chat

import top.wkbin.zaomeng.data.api.TranscriptItemDto
import kotlin.test.Test
import kotlin.test.assertEquals

/** 断连重试 / 失败恢复时 transcript 合并必须按 turn_id 幂等，避免整段历史重复。 */
class MergeTranscriptTest {
    private fun item(turnId: String, speaker: String, message: String) = TranscriptItemDto(
        speaker = speaker,
        message = message,
        innerThought = "",
        role = "assistant",
        turnId = turnId,
        timestamp = "",
    )

    @Test
    fun `overlapping append replaces instead of duplicating`() {
        val history = listOf(item("t1", "甲", "第一句"), item("t2", "乙", "第二句"))
        val append = listOf(item("t2", "乙", "第二句"), item("t3", "丙", "第三句"))

        val merged = mergeTranscript(history, append)

        assertEquals(listOf("t1", "t2", "t3"), merged.map { it.turnId })
        assertEquals(3, merged.size)
    }

    @Test
    fun `whole history replay dedupes to single copy`() {
        val history = listOf(item("t1", "甲", "第一句"), item("t2", "乙", "第二句"))
        val append = history

        val merged = mergeTranscript(history, append)

        assertEquals(listOf("t1", "t2"), merged.map { it.turnId })
        assertEquals(2, merged.size)
    }

    @Test
    fun `non overlapping append stays append only`() {
        val history = listOf(item("t1", "甲", "第一句"))
        val append = listOf(item("t2", "乙", "第二句"))

        val merged = mergeTranscript(history, append)

        assertEquals(listOf("t1", "t2"), merged.map { it.turnId })
    }
}

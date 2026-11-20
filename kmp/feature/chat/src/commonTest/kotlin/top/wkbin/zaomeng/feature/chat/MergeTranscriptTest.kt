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

    @Test
    fun `three earlier pages cover 250 messages without gaps`() {
        val all = (0 until 250).map { index ->
            TranscriptItemDto(seq = index, turnId = "turn-${index / 2}", message = "消息$index")
        }
        var transcript = all.drop(150)
        var nextOffset: Int? = null

        val secondOffset = resolveEarlierOffset(nextOffset, transcript.size)
        assertEquals(100, secondOffset)
        val secondPage = all.subList(50, 150).asReversed()
        transcript = mergeEarlierTranscript(transcript, secondPage.asReversed())
        nextOffset = 200
        assertEquals((50..249).toList(), transcript.map { it.seq })

        val thirdOffset = resolveEarlierOffset(nextOffset, transcript.size)
        assertEquals(200, thirdOffset)
        val thirdPage = all.take(50).asReversed()
        transcript = mergeEarlierTranscript(transcript, thirdPage.asReversed())

        assertEquals((0..249).toList(), transcript.map { it.seq })
        assertEquals(250, transcript.distinctBy { it.seq }.size)
    }
}

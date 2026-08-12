package top.wkbin.zaomeng.feature.redistill

import top.wkbin.zaomeng.data.api.RedistillSegmentDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedistillSelectionTest {
    @Test
    fun `parses multiple redistill characters without duplicates`() {
        assertEquals(
            listOf("宋江", "林冲", "武松"),
            parseRedistillCharacters("宋江、林冲\n武松，宋江"),
        )
    }

    @Test
    fun `combines selected segments for multiple characters and removes duplicate text`() {
        val shared = RedistillSegmentDto(segmentId = "seg-1", fullText = "共同场景正文。")
        val unique = RedistillSegmentDto(segmentId = "seg-2", fullText = "林冲独有正文。")

        val combined = combineRedistillSegments(
            listOf(
                RedistillSelectedSegment("宋江", shared),
                RedistillSelectedSegment("林冲", shared),
                RedistillSelectedSegment("林冲", unique),
            ),
        )

        assertEquals(1, "共同场景正文。".toRegex().findAll(combined).count())
        assertTrue("【宋江·原文推进片段】" in combined)
        assertTrue("【林冲·原文推进片段】\n林冲独有正文。" in combined)
        assertEquals("宋江::seg-1", redistillSegmentKey("宋江", "seg-1"))
    }
}

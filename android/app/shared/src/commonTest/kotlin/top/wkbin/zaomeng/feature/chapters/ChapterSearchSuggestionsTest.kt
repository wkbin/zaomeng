package top.wkbin.zaomeng.feature.chapters

import kotlin.test.Test
import kotlin.test.assertEquals
import top.wkbin.zaomeng.data.api.ChapterDto
import top.wkbin.zaomeng.data.api.DialogueSessionDto

class ChapterSearchSuggestionsTest {
    @Test
    fun `collects unique character suggestions in story order`() {
        val suggestions = chapterSearchSuggestions(
            chapters = listOf(
                ChapterDto(participants = listOf("黛玉", "宝玉")),
                ChapterDto(participants = listOf("宝玉", "宝钗")),
            ),
            sessions = listOf(DialogueSessionDto(participants = listOf("凤姐", "黛玉"))),
        )

        assertEquals(listOf("黛玉", "宝玉", "宝钗", "凤姐"), suggestions)
    }
}

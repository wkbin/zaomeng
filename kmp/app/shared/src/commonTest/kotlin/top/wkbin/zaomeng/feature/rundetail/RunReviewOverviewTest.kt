package top.wkbin.zaomeng.feature.rundetail

import kotlin.test.Test
import kotlin.test.assertEquals
import top.wkbin.zaomeng.data.api.PersonaIssueDto
import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
import top.wkbin.zaomeng.data.api.RelationDetailsDto
import top.wkbin.zaomeng.data.api.WorldMemoryDto
import top.wkbin.zaomeng.data.api.WorldTimelineItemDto

class RunReviewOverviewTest {
    @Test
    fun `summarizes existing relation timeline and persona review signals`() {
        val overview = buildRunReviewOverview(
            relations = RelationDetailsDto(conflictCount = 2),
            worldMemory = WorldMemoryDto(
                timeline = listOf(
                    WorldTimelineItemDto(consistencyStatus = "pass"),
                    WorldTimelineItemDto(consistencyStatus = "warning"),
                ),
            ),
            qualityReports = listOf(
                PersonaQualityReportDto(character = "黛玉", score = 92),
                PersonaQualityReportDto(
                    character = "宝玉",
                    score = 86,
                    issues = listOf(PersonaIssueDto(message = "缺少说话风格")),
                ),
                PersonaQualityReportDto(character = "凤姐", score = 72),
            ),
        )

        assertEquals(2, overview.relationConflictCount)
        assertEquals(1, overview.timelineWarningCount)
        assertEquals(listOf("宝玉", "凤姐"), overview.charactersNeedingReview)
        assertEquals(3, overview.checkedCharacterCount)
    }
}

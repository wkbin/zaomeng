package top.wkbin.zaomeng.feature.rundetail

import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
import top.wkbin.zaomeng.data.api.RelationDetailsDto
import top.wkbin.zaomeng.data.api.WorldMemoryDto

data class RunReviewOverview(
    val relationConflictCount: Int = 0,
    val timelineWarningCount: Int = 0,
    val charactersNeedingReview: List<String> = emptyList(),
    val checkedCharacterCount: Int = 0,
)

internal fun buildRunReviewOverview(
    relations: RelationDetailsDto?,
    worldMemory: WorldMemoryDto?,
    qualityReports: List<PersonaQualityReportDto>,
): RunReviewOverview = RunReviewOverview(
    relationConflictCount = relations?.conflictCount ?: 0,
    timelineWarningCount = worldMemory?.timeline?.count { item ->
        item.consistencyStatus !in setOf("", "pass")
    } ?: 0,
    charactersNeedingReview = qualityReports
        .filter { report -> report.score < 80 || report.issues.isNotEmpty() }
        .map(PersonaQualityReportDto::character)
        .filter(String::isNotBlank)
        .distinct(),
    checkedCharacterCount = qualityReports.size,
)

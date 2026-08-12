package top.wkbin.zaomeng.domain.run

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.ArtifactIndexDto
import top.wkbin.zaomeng.data.api.PersonaIndexDto
import top.wkbin.zaomeng.data.api.PersonaIssueDto
import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
import top.wkbin.zaomeng.data.api.RelationDetailsDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.WorldMemoryDto
import top.wkbin.zaomeng.data.api.WorldTimelineItemDto

class LoadRunReviewUseCaseTest {
    private val run = RunManifestDto(
        runId = "run-1",
        artifactIndex = ArtifactIndexDto(
            characters = listOf(
                PersonaIndexDto(name = "黛玉"),
                PersonaIndexDto(name = "凤姐"),
            ),
        ),
    )

    @Test
    fun `loads review signals through gateway and aggregates overview`() = runTest {
        val gateway = FakeRunReviewGateway(
            relations = RelationDetailsDto(conflictCount = 2),
            worldMemory = WorldMemoryDto(
                timeline = listOf(
                    WorldTimelineItemDto(consistencyStatus = "pass"),
                    WorldTimelineItemDto(consistencyStatus = "warning"),
                ),
            ),
            qualityReports = mapOf(
                "黛玉" to PersonaQualityReportDto(character = "黛玉", score = 92),
                "凤姐" to PersonaQualityReportDto(
                    character = "凤姐",
                    score = 72,
                    issues = listOf(PersonaIssueDto(message = "缺少说话风格")),
                ),
            ),
        )

        val overview = LoadRunReviewUseCase(gateway)(run)

        assertEquals(2, overview.relationConflictCount)
        assertEquals(1, overview.timelineWarningCount)
        assertEquals(listOf("凤姐"), overview.charactersNeedingReview)
        assertEquals(2, overview.checkedCharacterCount)
        assertEquals(listOf("黛玉", "凤姐"), gateway.requestedCharacters)
    }

    @Test
    fun `tolerates missing optional review signals`() = runTest {
        val gateway = FakeRunReviewGateway(fail = true)

        val overview = LoadRunReviewUseCase(gateway)(run)

        assertEquals(0, overview.relationConflictCount)
        assertEquals(0, overview.timelineWarningCount)
        assertTrue(overview.charactersNeedingReview.isEmpty())
        assertEquals(0, overview.checkedCharacterCount)
    }
}

private class FakeRunReviewGateway(
    private val relations: RelationDetailsDto? = null,
    private val worldMemory: WorldMemoryDto? = null,
    private val qualityReports: Map<String, PersonaQualityReportDto> = emptyMap(),
    private val fail: Boolean = false,
) : RunReviewGateway {
    val requestedCharacters = mutableListOf<String>()

    override suspend fun getRelations(runId: String): RelationDetailsDto {
        if (fail) error("relations unavailable")
        return relations ?: RelationDetailsDto()
    }

    override suspend fun getWorldMemory(runId: String): WorldMemoryDto {
        if (fail) error("world memory unavailable")
        return worldMemory ?: WorldMemoryDto()
    }

    override suspend fun getPersonaQuality(
        runId: String,
        character: String,
    ): PersonaQualityReportDto {
        if (fail) error("persona quality unavailable")
        requestedCharacters += character
        return qualityReports[character] ?: PersonaQualityReportDto(character = character)
    }
}

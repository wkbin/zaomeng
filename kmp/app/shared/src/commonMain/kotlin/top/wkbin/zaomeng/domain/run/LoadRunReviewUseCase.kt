package top.wkbin.zaomeng.domain.run

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
import top.wkbin.zaomeng.data.api.RelationDetailsDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.WorldMemoryDto

interface RunReviewGateway {
    suspend fun getRelations(runId: String): RelationDetailsDto
    suspend fun getWorldMemory(runId: String): WorldMemoryDto
    suspend fun getPersonaQuality(runId: String, character: String): PersonaQualityReportDto
}

data class RunReviewOverview(
    val relationConflictCount: Int = 0,
    val timelineWarningCount: Int = 0,
    val charactersNeedingReview: List<String> = emptyList(),
    val checkedCharacterCount: Int = 0,
)

/** Loads independent review signals concurrently and tolerates a missing optional signal. */
class LoadRunReviewUseCase(
    private val gateway: RunReviewGateway,
) {
    suspend operator fun invoke(run: RunManifestDto): RunReviewOverview = coroutineScope {
        val relations = async { optional { gateway.getRelations(run.runId) } }
        val worldMemory = async { optional { gateway.getWorldMemory(run.runId) } }
        val qualityReports = run.availableCharacters
            .take(MAX_QUALITY_REPORTS)
            .map { character -> async { optional { gateway.getPersonaQuality(run.runId, character) } } }
            .awaitAll()
            .filterNotNull()
        buildRunReviewOverview(
            relations = relations.await(),
            worldMemory = worldMemory.await(),
            qualityReports = qualityReports,
        )
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private companion object {
        const val MAX_QUALITY_REPORTS = 12
    }
}

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

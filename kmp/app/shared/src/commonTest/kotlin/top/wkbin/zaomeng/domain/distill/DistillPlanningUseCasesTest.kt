package top.wkbin.zaomeng.domain.distill

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import top.wkbin.zaomeng.data.api.RedistillSuggestionsDto
import top.wkbin.zaomeng.data.api.SamplingPlanDto

class DistillPlanningUseCasesTest {
    @Test
    fun samplingPolicyNormalizesSharedLimits() = runTest {
        val gateway = FakeDistillPlanningGateway()

        EstimateDistillSamplingUseCase(gateway)(
            charCount = -10,
            sentenceCount = -5,
            characterCount = 0,
            maxSentences = 1,
            maxChars = 999_999,
        )

        assertEquals(listOf(0, 0, 1, 20, 200_000), gateway.lastSamplingArguments)
    }

    @Test
    fun recommendationsAreDeduplicatedAndKeepCharacterOrder() = runTest {
        val gateway = FakeDistillPlanningGateway()

        val result = SuggestRedistillSegmentsUseCase(gateway)(
            runId = "run-1",
            characters = listOf(" 林冲 ", "鲁智深", "林冲", ""),
        )

        assertEquals(listOf("林冲", "鲁智深"), result.map { it.character })
        assertEquals(setOf("林冲", "鲁智深"), gateway.requestedCharacters.toSet())
    }
}

private class FakeDistillPlanningGateway : DistillPlanningGateway {
    var lastSamplingArguments: List<Int> = emptyList()
    val requestedCharacters = mutableListOf<String>()

    override suspend fun estimateSampling(
        charCount: Int,
        sentenceCount: Int,
        characterCount: Int,
        maxSentences: Int,
        maxChars: Int,
    ): SamplingPlanDto {
        lastSamplingArguments = listOf(charCount, sentenceCount, characterCount, maxSentences, maxChars)
        return SamplingPlanDto()
    }

    override suspend fun suggestRedistillSegments(
        runId: String,
        character: String,
        maxSegments: Int,
    ): RedistillSuggestionsDto {
        requestedCharacters += character
        return RedistillSuggestionsDto(character = character)
    }
}

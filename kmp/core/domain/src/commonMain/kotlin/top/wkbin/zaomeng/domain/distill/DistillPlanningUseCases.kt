package top.wkbin.zaomeng.domain.distill

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import top.wkbin.zaomeng.data.api.RedistillSuggestionsDto
import top.wkbin.zaomeng.data.api.SamplingPlanDto

interface DistillPlanningGateway {
    suspend fun estimateSampling(
        charCount: Int,
        sentenceCount: Int,
        characterCount: Int,
        maxSentences: Int,
        maxChars: Int,
    ): SamplingPlanDto

    suspend fun suggestRedistillSegments(
        runId: String,
        character: String,
        maxSegments: Int = 3,
    ): RedistillSuggestionsDto
}

data class CharacterRedistillSuggestions(
    val character: String,
    val suggestions: RedistillSuggestionsDto,
)

/** Centralizes the sampling limits shared by first import and subsequent distillation. */
class EstimateDistillSamplingUseCase(
    private val gateway: DistillPlanningGateway,
) {
    suspend operator fun invoke(
        charCount: Int,
        sentenceCount: Int,
        characterCount: Int,
        maxSentences: Int?,
        maxChars: Int?,
    ): SamplingPlanDto = gateway.estimateSampling(
        charCount = charCount.coerceAtLeast(0),
        sentenceCount = sentenceCount.coerceAtLeast(0),
        characterCount = characterCount.coerceAtLeast(1),
        maxSentences = maxSentences?.coerceIn(MIN_SENTENCES, MAX_SENTENCES) ?: DEFAULT_SENTENCES,
        maxChars = maxChars?.coerceIn(MIN_CHARS, MAX_CHARS) ?: DEFAULT_CHARS,
    )

    private companion object {
        const val MIN_SENTENCES = 20
        const val MAX_SENTENCES = 300
        const val DEFAULT_SENTENCES = 120
        const val MIN_CHARS = 2_000
        const val MAX_CHARS = 200_000
        const val DEFAULT_CHARS = 50_000
    }
}

/** Requests one recommendation set per character concurrently while preserving selection order. */
class SuggestRedistillSegmentsUseCase(
    private val gateway: DistillPlanningGateway,
) {
    suspend operator fun invoke(
        runId: String,
        characters: List<String>,
        maxSegments: Int = 3,
    ): List<CharacterRedistillSuggestions> = coroutineScope {
        characters
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .map { character ->
                async {
                    CharacterRedistillSuggestions(
                        character = character,
                        suggestions = gateway.suggestRedistillSegments(
                            runId = runId,
                            character = character,
                            maxSegments = maxSegments.coerceIn(1, 12),
                        ),
                    )
                }
            }
            .awaitAll()
    }
}

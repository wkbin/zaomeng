package top.wkbin.zaomeng.data.api

import kotlin.test.Test
import kotlin.test.assertEquals

class RunContractsTest {
    @Test
    fun `crossover uses source snapshots when legacy artifact index is empty`() {
        val run = RunManifestDto(
            betaFeature = BetaFeatureDto(
                kind = "cross_book_crossover",
                sourceSnapshots = listOf(
                    CrossoverSourceDto(runId = "run-a", character = "林晚"),
                    CrossoverSourceDto(runId = "run-b", character = "沈照"),
                ),
            ),
        )

        assertEquals(listOf("林晚", "沈照"), run.availableCharacters)
    }
}

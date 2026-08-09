package top.wkbin.zaomeng.feature.importbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import top.wkbin.zaomeng.data.api.RunManifestDto

class ImportRecoveryTest {
    @Test
    fun `selects the only matching run created after request started`() {
        val recovered = selectRecoveredRun(
            runs = listOf(run("old", "book"), run("new", "book")),
            knownRunIds = setOf("old"),
        ) { it.novelId == "book" }

        assertEquals("new", recovered?.runId)
    }

    @Test
    fun `does not recover when multiple new runs match`() {
        val recovered = selectRecoveredRun(
            runs = listOf(run("new-1", "book"), run("new-2", "book")),
            knownRunIds = emptySet(),
        ) { it.novelId == "book" }

        assertNull(recovered)
    }

    @Test
    fun `does not mistake an existing matching run for this operation`() {
        val recovered = selectRecoveredRun(
            runs = listOf(run("old", "book"), run("new", "other")),
            knownRunIds = setOf("old"),
        ) { it.novelId == "book" }

        assertNull(recovered)
    }

    private fun run(id: String, novelId: String) = RunManifestDto(runId = id, novelId = novelId)
}

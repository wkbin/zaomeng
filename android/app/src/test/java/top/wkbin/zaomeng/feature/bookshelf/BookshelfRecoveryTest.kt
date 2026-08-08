package top.wkbin.zaomeng.feature.bookshelf

import top.wkbin.zaomeng.data.api.RunControlDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.RunProgressDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookshelfRecoveryTest {
    @Test
    fun `interrupted run is detected from recovery metadata`() {
        val run = run("run-1", interrupted = true)

        assertTrue(run.isInterrupted)
    }

    @Test
    fun `stopped run without android interruption reason is not recoverable`() {
        assertFalse(run("stopped", interrupted = false).isInterrupted)
        assertFalse(run("running", interrupted = true, status = "running").isInterrupted)
    }

    @Test
    fun `recoverable runs excludes dismissed ids`() {
        val runs = listOf(run("run-1", interrupted = true), run("run-2", interrupted = true))

        val result = recoverableRuns(runs, dismissedRecoveredRunIds = setOf("run-1"))

        assertEquals(listOf("run-2"), result.map(RunManifestDto::runId))
    }

    private fun run(
        id: String,
        interrupted: Boolean,
        status: String = "stopped",
    ) = RunManifestDto(
        runId = id,
        status = status,
        progress = RunProgressDto(
            stage = if (interrupted) "interrupted" else "",
        ),
        control = RunControlDto(
            interruptionReason = if (interrupted) "android_process_ended" else "",
        ),
    )
}

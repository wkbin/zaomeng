package top.wkbin.zaomeng.feature.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatRequestIdentityTest {
    private val snapshot = ChatUiState(
        runId = "run-a",
        sessionId = "session-a",
        failedOperationId = "operation-a",
    )

    @Test
    fun `matching session and operation accepts current stream updates`() {
        assertTrue(snapshot.matchesSend(snapshot, "operation-a"))
    }

    @Test
    fun `session navigation rejects late stream and tool updates`() {
        val navigated = snapshot.copy(sessionId = "session-b")

        assertFalse(navigated.matchesSend(snapshot, "operation-a"))
        assertFalse(navigated.matchesSession(snapshot.runId, snapshot.sessionId))
    }

    @Test
    fun `retry replacement rejects events from previous operation`() {
        val retried = snapshot.copy(failedOperationId = "operation-b")

        assertFalse(retried.matchesSend(snapshot, "operation-a"))
        assertTrue(retried.matchesSend(retried, "operation-b"))
    }
}

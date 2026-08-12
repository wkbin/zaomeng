package top.wkbin.zaomeng.feature.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.DialogueSessionDto

class ChatUiStateTest {
    private val readySession = DialogueSessionDto(
        runId = "run-1",
        sessionId = "session-1",
        status = "ready",
    )

    @Test
    fun toolsRequireAnIdleReadySession() {
        val idle = ChatUiState(
            runId = readySession.runId,
            sessionId = readySession.sessionId,
            loading = false,
            session = readySession,
        )

        assertTrue(idle.canUseTools)
        assertFalse(idle.copy(loading = true).canUseTools)
        assertFalse(idle.copy(refreshing = true).canUseTools)
        assertFalse(idle.copy(sending = true).canUseTools)
        assertFalse(idle.copy(recovering = true).canUseTools)
        assertFalse(idle.copy(sendOutcomeUnknown = true).canUseTools)
        assertFalse(idle.copy(toolBusy = "review").canUseTools)
        assertFalse(idle.copy(session = readySession.copy(status = "pending")).canUseTools)
    }

    @Test
    fun refreshIsBlockedWhileAnotherSessionOperationIsActive() {
        val idle = ChatUiState(
            runId = readySession.runId,
            sessionId = readySession.sessionId,
            loading = false,
            session = readySession,
        )

        assertTrue(idle.canRefresh)
        assertTrue(idle.copy(sendOutcomeUnknown = true).canRefresh)
        assertFalse(idle.copy(loading = true).canRefresh)
        assertFalse(idle.copy(refreshing = true).canRefresh)
        assertFalse(idle.copy(sending = true).canRefresh)
        assertFalse(idle.copy(recovering = true).canRefresh)
        assertFalse(idle.copy(toolBusy = "memory").canRefresh)
    }
}

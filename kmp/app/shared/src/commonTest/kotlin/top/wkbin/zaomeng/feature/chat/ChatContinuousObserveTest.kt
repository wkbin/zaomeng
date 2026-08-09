package top.wkbin.zaomeng.feature.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.DialogueSessionDto

class ChatContinuousObserveTest {
    @Test
    fun continuousObserveIsOnlyAvailableForReadyObserveSessions() {
        val readyObserve = ChatUiState(
            loading = false,
            session = DialogueSessionDto(mode = "observe", status = "ready"),
        )
        val acting = readyObserve.copy(session = DialogueSessionDto(mode = "act", status = "ready"))
        val pending = readyObserve.copy(session = DialogueSessionDto(mode = "observe", status = "pending"))

        assertTrue(readyObserve.canToggleContinuousObserve)
        assertFalse(acting.canToggleContinuousObserve)
        assertFalse(pending.canToggleContinuousObserve)
    }

    @Test
    fun activeContinuousObserveKeepsPauseAvailableAndLocksManualSending() {
        val state = ChatUiState(
            loading = false,
            draft = "这句话不会在旁观时发出",
            continuousObserveEnabled = true,
            session = DialogueSessionDto(mode = "observe", status = "ready"),
        )

        assertTrue(state.canToggleContinuousObserve)
        assertFalse(state.canSend)
        assertFalse(state.canUseTools)
        assertFalse(state.canRefresh)
    }
}

package top.wkbin.zaomeng.feature.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.TranscriptItemDto

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

    @Test
    fun nextHintTakesPriorityForContinuousObservePrompt() {
        val session = DialogueSessionDto(
            runtimeStateOverview = JsonObject(mapOf("next_hint" to JsonPrimitive("推进雨夜追逐"))),
            transcript = listOf(TranscriptItemDto(role = "scene", message = "旧场景")),
        )

        assertEquals("推进雨夜追逐", ContinuousObserveController.buildPrompt(session))
    }

    @Test
    fun recentSceneProvidesFallbackContinuousObservePrompt() {
        val session = DialogueSessionDto(
            transcript = listOf(
                TranscriptItemDto(role = "scene", message = "两人在码头对峙"),
                TranscriptItemDto(role = "character", message = "你终于来了"),
            ),
        )

        assertEquals(
            "承接刚才的场景：两人在码头对峙",
            ContinuousObserveController.buildPrompt(session),
        )
    }
}

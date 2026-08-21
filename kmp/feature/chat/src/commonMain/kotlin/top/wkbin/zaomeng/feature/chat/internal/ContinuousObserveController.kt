package top.wkbin.zaomeng.feature.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.client.platform.clientRandomUuid
import top.wkbin.zaomeng.data.api.DialogueSessionDto

/** Owns continuous-observe activation, round scheduling, pause, and failure recovery. */
internal class ContinuousObserveController(
    private val scope: CoroutineScope,
    private val streamEngine: ChatStreamEngine,
    private val currentState: () -> ChatUiState,
    private val updateState: (((ChatUiState) -> ChatUiState) -> Unit),
    private val operationId: () -> String = ::clientRandomUuid,
) {
    private var scheduleJob: Job? = null
    private var sessionId = ""

    fun toggle() {
        val snapshot = currentState()
        if (snapshot.continuousObserveEnabled) {
            stop("已暂停连续旁观。")
            return
        }
        if (!snapshot.canToggleContinuousObserve) {
            updateState { it.copy(error = "仅可在就绪的旁观会话中开启连续旁观。") }
            return
        }
        sessionId = snapshot.sessionId
        updateState {
            it.copy(
                continuousObserveEnabled = true,
                notice = "连续旁观已开启。",
                error = "",
            )
        }
        startRound()
    }

    fun pause() {
        stop(notice = "")
    }

    fun stop(notice: String) {
        scheduleJob?.cancel()
        scheduleJob = null
        sessionId = ""
        updateState { current ->
            if (!current.continuousObserveEnabled) {
                current
            } else {
                current.copy(
                    continuousObserveEnabled = false,
                    notice = notice.ifBlank { current.notice },
                )
            }
        }
    }

    private fun startRound() {
        val snapshot = currentState()
        val session = snapshot.session
        if (
            !snapshot.continuousObserveEnabled ||
            snapshot.sessionId != sessionId ||
            session?.mode != "observe" ||
            session.status != "ready" ||
            snapshot.sending
        ) {
            if (snapshot.continuousObserveEnabled) {
                stop("连续旁观已暂停：会话状态已变化。")
            }
            return
        }
        streamEngine.start(
            snapshot = snapshot,
            message = buildPrompt(requireNotNull(session)),
            messageKind = "narration",
            operationId = operationId(),
            suppressTranscriptMessage = true,
            showPendingUserMessage = false,
            onComplete = {
                val current = currentState()
                if (current.continuousObserveEnabled && current.sessionId == sessionId) {
                    scheduleJob?.cancel()
                    scheduleJob = scope.launch {
                        delay(CONTINUOUS_OBSERVE_DELAY_MS)
                        startRound()
                    }
                }
            },
            onFailure = {
                stop("连续旁观已暂停：刚才这一轮生成失败。")
            },
        )
    }

    companion object {
        fun buildPrompt(session: DialogueSessionDto): String {
            val nextHint = session.runtimeStateOverview["next_hint"]
                ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                ?.trim()
                .orEmpty()
            if (nextHint.isNotBlank()) return nextHint

            val recentPrompt = session.transcript.asReversed()
                .firstOrNull { item -> item.role in setOf("scene", "director", "user") }
                ?.message
                ?.trim()
                .orEmpty()
            return if (recentPrompt.isNotBlank()) {
                "承接刚才的场景：$recentPrompt"
            } else {
                "让当前场景自然延续，保持人物关系和情绪变化一致。"
            }
        }

        private const val CONTINUOUS_OBSERVE_DELAY_MS = 480L
    }
}

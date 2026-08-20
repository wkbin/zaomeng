package top.wkbin.zaomeng.feature.chat

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.data.api.DialogueSessionDto

/** Pure scheduling input policy for continuous-observe rounds. */
internal object ContinuousObserveController {
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
}

package top.wkbin.zaomeng.feature.chat

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.data.api.DialogueSessionDto

internal data class ChatContextSummary(
    val mode: String,
    val scene: String,
    val participants: String,
)

internal fun chatContextSummary(session: DialogueSessionDto): ChatContextSummary {
    val scene = session.sceneCard["title"]?.jsonPrimitive?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: session.sceneCard["location"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotBlank)
        ?: session.sceneHistory.lastOrNull()?.get("title")?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotBlank)
        ?: "未设置场景"
    val participants = session.participants.filter(String::isNotBlank).distinct()
    return ChatContextSummary(
        mode = session.modeDisplay.ifBlank { session.mode.contextModeLabel() },
        scene = scene,
        participants = participants.take(3).joinToString("、").ifBlank { "未指定参与角色" } +
            if (participants.size > 3) " 等 ${participants.size} 人" else "",
    )
}

private fun String.contextModeLabel(): String = when (this) {
    "act" -> "扮演角色"
    "insert" -> "以我入场"
    "observe" -> "旁观"
    else -> "人物会话"
}

package top.wkbin.zaomeng.ktor.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 准备对话轮次请求
 */
@Serializable
data class PrepareDialogueTurnRequest(
    val message: String,
    @SerialName("message_kind")
    val messageKind: String = "user_input",
    @SerialName("include_inner_thoughts")
    val includeInnerThoughts: Boolean = false,
    @SerialName("operation_id") val operationId: String = ""
)

/**
 * 对话响应
 */
@Serializable
data class DialogueResponse(
    val speaker: String,
    val message: String,
    @SerialName("inner_thought")
    val innerThought: String? = null
)

/**
 * 对话轮次响应
 */
@Serializable
data class DialogueTurnResponse(
    @SerialName("turn_id")
    val turnId: String,
    val response: DialogueResponse
)

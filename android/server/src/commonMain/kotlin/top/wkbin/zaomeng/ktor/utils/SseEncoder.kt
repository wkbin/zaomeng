package top.wkbin.zaomeng.ktor.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Server-Sent Events (SSE) 编码器
 *
 * 将事件和数据编码为 SSE 格式，用于流式响应
 */
object SseEncoder {
    private val json = Json {
        prettyPrint = false
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /**
     * 编码 SSE 事件
     *
     * @param event 事件名称（默认 "message"）
     * @param payload 事件数据（将被序列化为 JSON）
     * @return SSE 格式的字符串
     */
    fun encodeEvent(event: String = "message", payload: Any): String {
        // 清理事件名：只保留小写字母、数字、下划线和连字符
        val eventName = event.lowercase()
            .replace(Regex("[^a-z0-9_-]"), "")
            .takeIf { it.isNotEmpty() } ?: "message"

        // 将 payload 序列化为 JSON
        val data = when (payload) {
            is String -> payload
            is JsonElement -> json.encodeToString(JsonElement.serializer(), payload)
            else -> {
                try {
                    json.encodeToJsonElement(payload).toString()
                } catch (e: Exception) {
                    // Fallback: use toString
                    payload.toString()
                }
            }
        }

        // SSE 格式：event: <name>\ndata: <json>\n\n
        return "event: $eventName\ndata: $data\n\n"
    }

    /**
     * 编码简单的 SSE 消息（使用默认 "message" 事件）
     */
    fun encodeMessage(payload: Any): String = encodeEvent("message", payload)

    /**
     * 编码多个字段的 SSE 事件
     */
    fun encodeEvent(event: String = "message", vararg pairs: Pair<String, Any?>): String {
        val payload = pairs.toMap().filterValues { it != null }
        return encodeEvent(event, payload)
    }
}

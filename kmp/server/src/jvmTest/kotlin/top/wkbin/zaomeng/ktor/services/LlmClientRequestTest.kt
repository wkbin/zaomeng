package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class LlmClientRequestTest {
    @Test
    fun `json output request serializes native response format`() {
        val request = LlmClient.ChatCompletionRequest(
            model = "deepseek-v4-flash",
            messages = listOf(LlmClient.ChatMessage(role = "user", content = "返回 JSON")),
            responseFormat = LlmClient.ResponseFormat(type = "json_object"),
        )

        val encoded = Json.encodeToString(request)
        val responseFormat = Json.parseToJsonElement(encoded).jsonObject["response_format"]?.jsonObject
        assertEquals("json_object", responseFormat?.get("type")?.jsonPrimitive?.content)
    }
}

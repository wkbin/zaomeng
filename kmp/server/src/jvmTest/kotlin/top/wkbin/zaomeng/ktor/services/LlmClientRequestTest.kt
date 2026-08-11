package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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

    @Test
    fun `stream payload consumer failures propagate instead of being treated as decode failures`() = runBlocking {
        var decodeFailureReported = false

        val error = assertFailsWith<IOException> {
            consumeDecodedSsePayload(
                data = "valid",
                decode = { "delta" },
                onDecodeFailure = { decodeFailureReported = true },
                consume = { throw IOException("Broken pipe") },
            )
        }

        assertEquals("Broken pipe", error.message)
        assertFalse(decodeFailureReported)
    }

    @Test
    fun `malformed stream payload is skipped without invoking consumer`() = runBlocking {
        var decodeFailureReported = false
        var consumed = false

        val decoded = consumeDecodedSsePayload(
            data = "invalid",
            decode = { throw IllegalArgumentException("malformed JSON") },
            onDecodeFailure = { decodeFailureReported = true },
            consume = { consumed = true },
        )

        assertFalse(decoded)
        assertEquals(true, decodeFailureReported)
        assertFalse(consumed)
    }
}

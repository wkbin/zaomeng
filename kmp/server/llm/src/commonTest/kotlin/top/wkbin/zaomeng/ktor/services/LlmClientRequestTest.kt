package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `cache diff locates first break cascade and truncation`() {
        val baseline = RequestCacheDiff.slots(listOf(
            LlmClient.ChatMessage("system", "stable"),
            LlmClient.ChatMessage("user", "old"),
            LlmClient.ChatMessage("assistant", "tail"),
        ))
        val current = RequestCacheDiff.slots(listOf(
            LlmClient.ChatMessage("system", "stable"),
            LlmClient.ChatMessage("user", "changed"),
            LlmClient.ChatMessage("assistant", "tail"),
        ))

        val diff = RequestCacheDiff.compare(baseline, current)
        assertEquals(1, diff.firstBreakIndex)
        assertEquals(current[0].estimatedTokens, diff.hitTokens)
        assertEquals(current[1].estimatedTokens, diff.breakTokens)
        assertEquals(current[2].estimatedTokens, diff.cascadeTokens)
        assertEquals(diff.breakTokens + diff.cascadeTokens, diff.invalidatedTokens)
        assertTrue(diff.estimatedPrefixHitRate!! > 0.0)

        val truncated = RequestCacheDiff.compare(baseline, current.take(2))
        assertEquals("truncated", truncated.status)
        assertTrue(truncated.truncated)
    }

    @Test
    fun `request diagnostics separates sessions usage and raw text`() {
        val diagnostics = ModelRequestDiagnostics()
        val context = LlmRequestContext(runId = "run-1", sessionId = "session-1")
        val first = LlmClient.ChatCompletionRequest(
            model = "m",
            messages = listOf(LlmClient.ChatMessage("system", "stable"), LlmClient.ChatMessage("user", "old")),
        )
        val firstId = diagnostics.start(context, first, "https://api.example.com/v1")
        diagnostics.finish(firstId, LlmClient.Usage(prompt_tokens = 20))

        val second = first.copy(
            messages = listOf(LlmClient.ChatMessage("system", "stable"), LlmClient.ChatMessage("user", "secret message"),
        ))
        val secondId = diagnostics.start(context, second, "https://api.example.com/v1")
        diagnostics.finish(
            secondId,
            LlmClient.Usage(
                prompt_tokens = 20,
                prompt_tokens_details = LlmClient.PromptTokenDetails(cached_tokens = 5),
            ),
        )

        val latest = diagnostics.latest("run-1", "session-1")!!
        assertEquals(1, latest.diff.firstBreakIndex)
        assertEquals(5, latest.usage?.cacheReadTokens)
        assertEquals(0.25, latest.usage?.cacheHitRate)
        assertTrue(latest.usage?.observed == true)
        assertEquals(null, diagnostics.latest("run-1", "other"))
        val exported = Json.encodeToString(diagnostics.exportRecent())
        assertFalse(exported.contains("secret message"))
    }
    @Test
    fun `namespace uses real endpoint and failed requests do not form baselines`() {
        val diagnostics = ModelRequestDiagnostics()
        val context = LlmRequestContext(runId = "run-1", sessionId = "session-1")
        val request = LlmClient.ChatCompletionRequest(
            model = "m",
            messages = listOf(LlmClient.ChatMessage("system", "stable")),
        )
        val firstId = diagnostics.start(context, request, "http://example.com/v1")
        diagnostics.finish(firstId)

        val secondId = diagnostics.start(context, request, "https://example.com/v1")
        diagnostics.finish(secondId)
        assertEquals("namespace-changed", diagnostics.latest("run-1", "session-1")?.diff?.status)

        val failedId = diagnostics.start(context.copy(sessionId = "failed"), request, "https://example.com/v1")
        diagnostics.finish(failedId, status = "failed")
        val successId = diagnostics.start(context.copy(sessionId = "failed"), request, "https://example.com/v1")
        diagnostics.finish(successId)
        assertEquals("no-previous", diagnostics.latest("run-1", "failed")?.diff?.status)
    }

    @Test
    fun `out of order completion cannot replace newer latest or baseline`() {
        val diagnostics = ModelRequestDiagnostics()
        val context = LlmRequestContext(runId = "run-1", sessionId = "session-1")
        val first = LlmClient.ChatCompletionRequest(
            model = "m",
            messages = listOf(LlmClient.ChatMessage("user", "first")),
        )
        val second = first.copy(messages = listOf(LlmClient.ChatMessage("user", "second")))
        val firstId = diagnostics.start(context, first, "https://example.com/v1")!!
        val secondId = diagnostics.start(context, second, "https://example.com/v1")!!

        diagnostics.finish(secondId)
        diagnostics.finish(firstId)
        assertEquals(secondId, diagnostics.latest("run-1", "session-1")?.requestId)
        assertEquals(
            "out-of-order",
            diagnostics.exportRecent().first { it.requestId == firstId }.diff.status,
        )

        val thirdId = diagnostics.start(context, second, "https://example.com/v1")!!
        diagnostics.finish(thirdId)
        val latest = diagnostics.latest("run-1", "session-1")!!
        assertEquals("compared", latest.diff.status)
        assertEquals(-1, latest.diff.firstBreakIndex)
    }

    @Test
    fun `request ids are unique and anthropic stream usage is enabled`() {
        val diagnostics = ModelRequestDiagnostics()
        val context = LlmRequestContext(runId = "run-1", sessionId = "session-1")
        val request = LlmClient.ChatCompletionRequest(model = "m", messages = emptyList())
        val ids = (0 until 100).map {
            diagnostics.start(context, request, "https://example.com/v1")
        }
        assertEquals(100, ids.toSet().size)
        val options = LlmClient.streamUsageOptions(mapOf(
            "provider" to "anthropic",
            "base_url" to "https://api.anthropic.com/v1",
        ))
        assertEquals(true, options?.include_usage)
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

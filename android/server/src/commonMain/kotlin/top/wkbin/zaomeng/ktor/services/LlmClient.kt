package top.wkbin.zaomeng.ktor.services

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.createHttpClientEngine
import top.wkbin.zaomeng.platform.nowEpochMillis
import kotlin.time.Duration.Companion.seconds

/**
 * LLM HTTP client with retry logic and streaming support.
 *
 * This service handles HTTP communication with LLM providers (OpenAI-compatible APIs).
 * Features:
 * - Automatic retry with exponential backoff
 * - Timeout configuration
 * - Streaming response support
 * - Provider-agnostic (OpenAI, Anthropic, custom endpoints)
 */
class LlmClient(
    private val modelApiKeyService: ModelApiKeyService,
    private val storageService: StorageService
) {
    companion object {
        private const val TAG = "LlmClient"
        private const val DEFAULT_TIMEOUT_SECONDS = 60L
        private const val DEFAULT_MAX_RETRIES = 3
        private const val DEFAULT_TEMPERATURE = 0.7
        // Retry-able HTTP status codes
        private val RETRYABLE_STATUS_CODES = setOf(408, 429, 500, 502, 503, 504)

        /**
         * 对齐 Python _apply_reasoning_controls（llm_client.py:67-115）：请求的 thinking / reasoning_effort 参数
         * 完全由模型设置的 reasoning_effort 决定（显示开关 enableReasoning 不影响请求，只影响透传）：
         * - off：DeepSeek v4 直连（api.deepseek.com）→ thinking {"type":"disabled"} 跳过思考（TTFT 最短）；其他模型不发
         * - auto / 空：什么都不发（DeepSeek 默认推理）
         * - low/medium/high/xhigh：OpenAI 推理模型发原值；DeepSeek v4 发映射值（medium→low，xhigh→max）；其他模型不发
         */
        val DISABLED_THINKING: JsonObject = buildJsonObject { put("type", "disabled") }

        /** 解析 thinking / reasoning_effort 请求参数（见 DISABLED_THINKING 注释）。 */
        fun resolveReasoningParams(profile: Map<String, String>): Pair<JsonObject?, String?> {
            val model = profile["model"]?.trim()?.lowercase().orEmpty()
            val baseUrl = profile["base_url"]?.trim()?.lowercase().orEmpty()
            val effort = profile["reasoning_effort"]?.trim()?.lowercase().orEmpty()
            val isDeepSeekV4 = model.startsWith("deepseek-v4-")
            val isDeepSeekV4Direct = isDeepSeekV4 && "api.deepseek.com" in baseUrl
            val isOpenAiReasoning = model.startsWith("gpt-5") ||
                model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4")
            if (effort.isEmpty() || effort == "auto") return null to null
            if (effort == "off") {
                // off：仅 DeepSeek 直连发 thinking disabled；其他模型什么都不发
                return if (isDeepSeekV4Direct) DISABLED_THINKING to null else null to null
            }
            // low/medium/high/xhigh
            if (isOpenAiReasoning) return null to effort // OpenAI 推理模型用原值
            if (isDeepSeekV4) {
                val deepseekEffort = when (effort) {
                    "medium" -> "low"
                    "xhigh" -> "max"
                    else -> effort // low / high
                }
                return null to deepseekEffort
            }
            return null to null
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        // null 字段不输出：thinking=null（开推理）时请求体不携带 thinking 字段
        explicitNulls = false
    }

    private val httpClient = HttpClient(createHttpClientEngine()) {
        install(ContentNegotiation) {
            json(json)
        }

        install(HttpTimeout) {
            requestTimeoutMillis = DEFAULT_TIMEOUT_SECONDS * 1000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = DEFAULT_TIMEOUT_SECONDS * 1000
        }

        // Don't throw exceptions on non-2xx responses
        expectSuccess = false
    }

    @Serializable
    data class ChatMessage(
        // 流式 chunk 的 delta 常缺 role（仅首个 chunk 带 "assistant"），故设默认值
        val role: String = "assistant",
        // 可空：DeepSeek 等 reasoning 模型流式 chunk 的 delta.content 可能为 null（内容在 reasoning_content）
        val content: String? = null
    )

    /**
     * 流式响应专用 DTO（OpenAI/DeepSeek SSE chunk）。
     * 与 ChatMessage 隔离：delta 缺 role、content 可空、含 reasoning_content。
     */
    @Serializable
    data class StreamChunk(
        val id: String? = null,
        val model: String = "",
        val choices: List<StreamChoice> = emptyList(),
    )

    @Serializable
    data class StreamChoice(
        val index: Int = 0,
        val delta: StreamDelta? = null,
        @SerialName("finish_reason")
        val finishReason: String? = null,
    )

    @Serializable
    data class StreamDelta(
        val role: String? = null,
        val content: String? = null,
        @SerialName("reasoning_content")
        val reasoningContent: String? = null,
    )

    @Serializable
    data class ChatCompletionRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double = DEFAULT_TEMPERATURE,
        val max_tokens: Int? = null,
        val stream: Boolean = false,
        // DeepSeek 推理控制：{"type":"disabled"} 关闭推理（缩短 TTFT）；null 不输出（保留默认推理）
        val thinking: JsonObject? = null,
        // DeepSeek reasoning_effort（low/medium/high/xhigh→max 等）：控制思考预算；null 不输出
        @SerialName("reasoning_effort")
        val reasoningEffort: String? = null,
    )

    @Serializable
    data class ChatCompletionResponse(
        val id: String? = null,
        val model: String,
        val choices: List<Choice>,
        val usage: Usage? = null
    )

    @Serializable
    data class Choice(
        val index: Int,
        val message: ChatMessage? = null,
        val delta: ChatMessage? = null,
        val finish_reason: String? = null
    )

    @Serializable
    data class Usage(
        val prompt_tokens: Int,
        val completion_tokens: Int,
        val total_tokens: Int
    )

    @Serializable
    data class ErrorResponse(
        val error: ErrorDetail
    )

    @Serializable
    data class ErrorDetail(
        val message: String,
        val type: String? = null,
        val code: String? = null
    )

    /**
     * Load model settings from storage.
     */
    private fun loadModelSettings(): JsonObject? {
        return try {
            val settingsFile = storageService.getModelSettingsPath()
            if (!storageService.exists(settingsFile)) {
                PlatformLog.w(TAG, "Model settings file not found: ${settingsFile}")
                return null
            }
            json.decodeFromString<JsonObject>(storageService.readText(settingsFile))
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to load model settings", e)
            null
        }
    }

    /**
     * Get the active profile configuration.
     */
    private fun getActiveProfile(): Map<String, String> {
        return try {
            val settings = storageService.readModelSettings() ?: return emptyMap()
            val profile = settings.profiles.firstOrNull { it.profileId == settings.activeProfileId }
                ?: settings.profiles.firstOrNull()
                ?: return emptyMap()
            val provider = profile.provider.orEmpty()
            val model = profile.model.orEmpty()
            val baseUrl = profile.baseUrl?.ifBlank { null } ?: "https://api.openai.com/v1"
            val profileId = profile.profileId.orEmpty()

            mapOf(
                "provider" to provider,
                "model" to model,
                "base_url" to baseUrl,
                "profile_id" to profileId,
                "reasoning_effort" to (profile.reasoningEffort ?: "off")
            )
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to parse model settings", e)
            emptyMap()
        }
    }

    /** 当前模型是否已配置（有激活 profile 的 model 且已存 API key）。 */
    fun isConfigured(): Boolean {
        val profile = getActiveProfile()
        if (profile["model"]?.trim().isNullOrEmpty()) return false
        val profileId = profile["profile_id"].orEmpty()
        return runCatching { modelApiKeyService.getApiKey(profileId) }.getOrNull()?.isNotBlank() == true
    }

    /**
     * Make a chat completion request (non-streaming).
     *
     * @param messages List of chat messages
     * @param model Optional model override
     * @param temperature Optional temperature override
     * @param maxTokens Optional max tokens override
     * @return Chat completion response
     * @throws Exception on API errors or network failures
     */
    suspend fun chatCompletion(
        messages: List<ChatMessage>,
        model: String? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
        enableReasoning: Boolean = false,
    ): ChatCompletionResponse {
        val profile = getActiveProfile()
        val resolvedModel = model ?: profile["model"] ?: throw IllegalStateException("No model configured")
        val baseUrl = profile["base_url"] ?: "https://api.openai.com/v1"
        val profileId = profile["profile_id"] ?: "default"

        val apiKey = modelApiKeyService.getApiKey(profileId)
            ?: throw IllegalStateException("No API key configured for profile: $profileId")

        val (thinking, reasoningEffort) = resolveReasoningParams(profile)
        val request = ChatCompletionRequest(
            model = resolvedModel,
            messages = messages,
            temperature = temperature ?: DEFAULT_TEMPERATURE,
            max_tokens = maxTokens,
            stream = false,
            thinking = thinking,
            reasoningEffort = reasoningEffort,
        )

        return retryWithBackoff(maxRetries = DEFAULT_MAX_RETRIES) {
            val response: HttpResponse = httpClient.post("$baseUrl/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(request)
            }

            when (response.status) {
                HttpStatusCode.OK -> {
                    response.body<ChatCompletionResponse>()
                }
                in RETRYABLE_STATUS_CODES.map { HttpStatusCode.fromValue(it) } -> {
                    val errorBody = response.bodyAsText()
                    PlatformLog.w(TAG, "Retryable error (${response.status}): $errorBody")
                    throw RetryableException("API returned ${response.status}")
                }
                else -> {
                    val errorBody = response.bodyAsText()
                    val errorMsg = try {
                        json.decodeFromString<ErrorResponse>(errorBody).error.message
                    } catch (e: Exception) {
                        errorBody
                    }
                    throw IllegalStateException("API error (${response.status}): $errorMsg")
                }
            }
        }
    }

    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): Result<Unit> {
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            return Result.failure(IllegalArgumentException("baseUrl, apiKey and model are required"))
        }
        return runCatching {
            val response = httpClient.post("${baseUrl.trimEnd('/')}/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(ChatCompletionRequest(
                    model = model,
                    messages = listOf(ChatMessage("user", "Reply with OK.")),
                    temperature = 0.0,
                    max_tokens = 8,
                    stream = false
                ))
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Model API returned ${response.status.value}")
            }
        }
    }

    /**
     * Make a chat completion request with streaming (callback-based).
     *
     * @param messages List of chat messages
     * @param onDelta Callback for each content delta
     * @param model Optional model override
     * @param temperature Optional temperature override
     * @param maxTokens Optional max tokens override
     * @return Final accumulated response
     * @throws Exception on API errors or network failures
     */
    @Suppress("DEPRECATION") // readUTF8Line 刻意用于逐行流式（EOF 返回 null），见下方注释
    suspend fun chatCompletionStream(
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        model: String? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
        onReasoning: (String) -> Unit = {},
        enableReasoning: Boolean = false,
    ): ChatCompletionResponse {
        val profile = getActiveProfile()
        val resolvedModel = model ?: profile["model"] ?: throw IllegalStateException("No model configured")
        val baseUrl = profile["base_url"] ?: "https://api.openai.com/v1"
        val profileId = profile["profile_id"] ?: "default"

        val apiKey = modelApiKeyService.getApiKey(profileId)
            ?: throw IllegalStateException("No API key configured for profile: $profileId")

        val (thinking, reasoningEffort) = resolveReasoningParams(profile)
        val request = ChatCompletionRequest(
            model = resolvedModel,
            messages = messages,
            temperature = temperature ?: DEFAULT_TEMPERATURE,
            max_tokens = maxTokens,
            stream = true,
            thinking = thinking,
            reasoningEffort = reasoningEffort,
        )

        val response: HttpResponse = httpClient.post("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            val errorMsg = try {
                json.decodeFromString<ErrorResponse>(errorBody).error.message
            } catch (e: Exception) {
                errorBody
            }
            throw IllegalStateException("API error (${response.status}): $errorMsg")
        }

        // Parse SSE stream：逐行挂起读取（对齐 Python iter_lines(chunk_size=1)，
        // readUTF8Line 读到 \n 即返回，避免任何整块缓冲）
        val contentBuilder = StringBuilder()
        var finishReason: String? = null
        var responseModel = resolvedModel

        val channel = response.bodyAsChannel()
        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (!line.startsWith("data: ")) {
                continue
            }
            val data = line.substring(6).trim()
            if (data == "[DONE]") {
                continue
            }

            try {
                val chunk = json.decodeFromString<StreamChunk>(data)
                responseModel = chunk.model
                val delta = chunk.choices.firstOrNull()?.delta
                val deltaContent = delta?.content
                if (deltaContent != null && deltaContent.isNotBlank()) {
                    contentBuilder.append(deltaContent)
                    onDelta(deltaContent)
                }
                delta?.reasoningContent?.takeIf { it.isNotBlank() }?.let { onReasoning?.invoke(it) }
                chunk.choices.firstOrNull()?.finishReason?.let {
                    finishReason = it
                }
            } catch (e: Exception) {
                PlatformLog.w(TAG, "Failed to parse SSE chunk: $data", e)
            }
        }

        // Return final accumulated response
        return ChatCompletionResponse(
            model = responseModel,
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = contentBuilder.toString()),
                    finish_reason = finishReason
                )
            ),
            usage = null // Usage not available in streaming mode
        )
    }

    /**
     * Make a chat completion request with streaming (Flow-based).
     *
     * @param messages List of chat messages
     * @param model Optional model override
     * @param temperature Optional temperature override
     * @param maxTokens Optional max tokens override
     * @return Flow of content deltas
     * @throws Exception on API errors or network failures
     */
    @Suppress("DEPRECATION") // readUTF8Line 刻意用于逐行流式（EOF 返回 null），见下方注释
    fun chatCompletionStream(
        messages: List<ChatMessage>,
        model: String? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
        onReasoning: (suspend (String) -> Unit)? = null,
        enableReasoning: Boolean = false,
    ): Flow<String> = flow {
        val profile = getActiveProfile()
        val resolvedModel = model ?: profile["model"] ?: throw IllegalStateException("No model configured")
        val baseUrl = profile["base_url"] ?: "https://api.openai.com/v1"
        val profileId = profile["profile_id"] ?: "default"

        val apiKey = modelApiKeyService.getApiKey(profileId)
            ?: throw IllegalStateException("No API key configured for profile: $profileId")

        val (thinking, reasoningEffort) = resolveReasoningParams(profile)
        val request = ChatCompletionRequest(
            model = resolvedModel,
            messages = messages,
            temperature = temperature ?: DEFAULT_TEMPERATURE,
            max_tokens = maxTokens,
            stream = true,
            thinking = thinking,
            reasoningEffort = reasoningEffort,
        )

        val response: HttpResponse = httpClient.post("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            val errorMsg = try {
                json.decodeFromString<ErrorResponse>(errorBody).error.message
            } catch (e: Exception) {
                errorBody
            }
            throw IllegalStateException("API error (${response.status}): $errorMsg")
        }

        // Parse SSE stream and emit deltas：逐行挂起读取（对齐 Python iter_lines(chunk_size=1)）
        val channel = response.bodyAsChannel()
        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (!line.startsWith("data: ")) {
                continue
            }
            val data = line.substring(6).trim()
            if (data == "[DONE]") {
                continue
            }

            try {
                val chunk = json.decodeFromString<StreamChunk>(data)
                val delta = chunk.choices.firstOrNull()?.delta
                val deltaContent = delta?.content
                if (deltaContent != null && deltaContent.isNotBlank()) {
                    PlatformLog.d(TAG, "emit@${nowEpochMillis()} ${deltaContent.take(20)}")
                    emit(deltaContent)
                }
                delta?.reasoningContent?.takeIf { it.isNotBlank() }?.let { onReasoning?.invoke(it) }
            } catch (e: Exception) {
                PlatformLog.w(TAG, "Failed to parse SSE chunk: $data", e)
            }
        }
    }

    /**
     * Retry logic with exponential backoff.
     */
    private suspend fun <T> retryWithBackoff(
        maxRetries: Int,
        initialDelayMs: Long = 1000,
        maxDelayMs: Long = 10000,
        factor: Double = 2.0,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelayMs
        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: RetryableException) {
                if (attempt == maxRetries - 1) {
                    throw e
                }
                PlatformLog.w(TAG, "Retry attempt ${attempt + 1}/$maxRetries after ${currentDelay}ms", e)
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
            }
        }
        return block() // Final attempt
    }

    /**
     * Exception indicating the request can be retried.
     */
    private class RetryableException(message: String) : Exception(message)

    /**
     * Close the HTTP client when done.
     */
    fun close() {
        httpClient.close()
    }
}

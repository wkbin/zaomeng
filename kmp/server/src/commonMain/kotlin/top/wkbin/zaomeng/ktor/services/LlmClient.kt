package top.wkbin.zaomeng.ktor.services

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.ModelCapabilityReportDto
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.createHttpClientEngine
import top.wkbin.zaomeng.platform.openStreamingHttpPost
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
                return when {
                    isDeepSeekV4Direct -> DISABLED_THINKING to null
                    isOpenAiReasoning -> null to "none"
                    else -> null to null
                }
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

        fun resolveTokenParameter(profile: Map<String, String>, model: String): String {
            val configured = profile["token_parameter"]?.trim()?.lowercase().orEmpty()
            if (configured in setOf("max_tokens", "max_completion_tokens")) return configured
            val normalizedModel = model.trim().lowercase()
            return if (
                normalizedModel.startsWith("gpt-5") || normalizedModel.startsWith("o1") ||
                normalizedModel.startsWith("o3") || normalizedModel.startsWith("o4")
            ) "max_completion_tokens" else "max_tokens"
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
    data class ResponseFormat(
        val type: String,
    )

    @Serializable
    data class ChatCompletionRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double? = DEFAULT_TEMPERATURE,
        val max_tokens: Int? = null,
        @SerialName("max_completion_tokens")
        val maxCompletionTokens: Int? = null,
        val stream: Boolean = false,
        // DeepSeek 推理控制：{"type":"disabled"} 关闭推理（缩短 TTFT）；null 不输出（保留默认推理）
        val thinking: JsonObject? = null,
        // DeepSeek reasoning_effort（low/medium/high/xhigh→max 等）：控制思考预算；null 不输出
        @SerialName("reasoning_effort")
        val reasoningEffort: String? = null,
        @SerialName("response_format")
        val responseFormat: ResponseFormat? = null,
    )

    private fun nativeJsonResponseFormat(
        profile: Map<String, String>,
        model: String,
        required: Boolean,
    ): ResponseFormat? {
        if (!required) return null
        when (profile["response_format_mode"]?.trim()?.lowercase()) {
            "json_object" -> return ResponseFormat(type = "json_object")
            "prompt_only" -> return null
        }
        val baseUrl = profile["base_url"]?.trim()?.lowercase().orEmpty()
        // DeepSeek V4 官方端点明确支持 Chat Completions JSON Output。
        // 未声明能力的第三方 OpenAI-compatible 服务仍靠提示词约束，避免请求参数不兼容。
        return if ("api.deepseek.com" in baseUrl && model.startsWith("deepseek-v4-")) {
            ResponseFormat(type = "json_object")
        } else {
            null
        }
    }

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
                "reasoning_effort" to (profile.reasoningEffort ?: "off"),
                "token_parameter" to (profile.tokenParameter ?: "auto"),
                "response_format_mode" to (profile.responseFormatMode ?: "auto")
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
        requireJsonObject: Boolean = false,
    ): ChatCompletionResponse {
        val profile = getActiveProfile()
        val resolvedModel = model ?: profile["model"] ?: throw IllegalStateException("No model configured")
        val baseUrl = profile["base_url"] ?: "https://api.openai.com/v1"
        val profileId = profile["profile_id"] ?: "default"

        val apiKey = modelApiKeyService.getApiKey(profileId)
            ?: throw IllegalStateException("No API key configured for profile: $profileId")

        val (thinking, reasoningEffort) = resolveReasoningParams(profile)
        val tokenParameter = resolveTokenParameter(profile, resolvedModel)
        val request = ChatCompletionRequest(
            model = resolvedModel,
            messages = messages,
            temperature = temperature ?: DEFAULT_TEMPERATURE,
            max_tokens = maxTokens.takeIf { tokenParameter == "max_tokens" },
            maxCompletionTokens = maxTokens.takeIf { tokenParameter == "max_completion_tokens" },
            stream = false,
            thinking = thinking,
            reasoningEffort = reasoningEffort,
            responseFormat = nativeJsonResponseFormat(profile, resolvedModel, requireJsonObject),
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

    suspend fun testConnection(
        baseUrl: String,
        apiKey: String,
        model: String,
        tokenParameter: String = "auto",
    ): Result<Unit> {
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            return Result.failure(IllegalArgumentException("baseUrl, apiKey and model are required"))
        }
        return runCatching {
            val resolvedTokenParameter = resolveTokenParameter(mapOf("token_parameter" to tokenParameter), model)
            val response = httpClient.post("${baseUrl.trimEnd('/')}/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(ChatCompletionRequest(
                    model = model,
                    messages = listOf(ChatMessage("user", "Reply with OK.")),
                    temperature = null,
                    max_tokens = 8.takeIf { resolvedTokenParameter == "max_tokens" },
                    maxCompletionTokens = 8.takeIf { resolvedTokenParameter == "max_completion_tokens" },
                    stream = false
                ))
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Model API returned ${response.status.value}")
            }
        }
    }

    /**
     * Probe an OpenAI-compatible endpoint instead of inferring capabilities from its provider label.
     * The streaming probe consumes SSE line-by-line so TTFT and chunk distribution describe what the
     * application will actually observe on this device.
     */
    suspend fun detectCapabilities(
        provider: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        reasoningEffort: String = "auto",
        tokenParameter: String = "auto",
        configuredMaxTokens: Int = 0,
    ): ModelCapabilityReportDto {
        require(baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()) {
            "baseUrl, apiKey and model are required"
        }
        val endpoint = "${baseUrl.trimEnd('/')}/chat/completions"
        val warnings = mutableListOf<String>()
        val preferredTokenParameter = resolveTokenParameter(mapOf("token_parameter" to tokenParameter), model)
        val alternateTokenParameter = if (preferredTokenParameter == "max_tokens") {
            "max_completion_tokens"
        } else {
            "max_tokens"
        }
        val workingTokenParameter = when {
            probeTokenParameter(endpoint, apiKey, model, preferredTokenParameter) -> preferredTokenParameter
            probeTokenParameter(endpoint, apiKey, model, alternateTokenParameter) -> {
                warnings += "当前 token 参数不兼容，建议改用 $alternateTokenParameter。"
                alternateTokenParameter
            }
            else -> {
                warnings += "max_tokens 与 max_completion_tokens 均未通过基础探测。"
                preferredTokenParameter
            }
        }

        val started = TimeSource.Monotonic.markNow()
        var ttftMs = 0
        var streamSupported = false
        var contentChunkCount = 0
        var lastContentAtMs = 0
        val chunkSizes = mutableListOf<Int>()
        val streamedContent = StringBuilder()
        val streamRequest = capabilityRequest(
            model = model,
            prompt = "Output exactly two JSON Lines. Each line must be one JSON object followed by a newline. No array, markdown, or extra text. Lines: {\"name\":\"apple\",\"score\":90} and {\"name\":\"banana\",\"score\":85}.",
            tokenParameter = workingTokenParameter,
            maxTokens = 160,
            stream = true,
        )
        runCatching {
            val response = openStreamingHttpPost(
                url = endpoint,
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                body = json.encodeToString(ChatCompletionRequest.serializer(), streamRequest),
            )
            try {
                if (response.statusCode !in 200..299) {
                    throw modelApiError(response.statusCode, response.statusDescription, response.readRemainingText())
                }
                streamSupported = true
                while (true) {
                    val data = sseData(response.readUtf8Line() ?: break) ?: continue
                    if (data == "[DONE]") continue
                    chunkSizes += data.encodeToByteArray().size
                    val chunk = runCatching { json.decodeFromString<StreamChunk>(data) }.getOrNull() ?: continue
                    chunk.choices.firstOrNull()?.delta?.content?.takeIf(String::isNotEmpty)?.let { delta ->
                        if (contentChunkCount == 0) {
                            ttftMs = started.elapsedNow().inWholeMilliseconds.coerceToInt()
                        }
                        lastContentAtMs = started.elapsedNow().inWholeMilliseconds.coerceToInt()
                        contentChunkCount += 1
                        streamedContent.append(delta)
                    }
                }
            } finally {
                response.close()
            }
        }.onFailure { warnings += "SSE 流式探测失败：${it.message.orEmpty().take(120)}" }
        val totalMs = started.elapsedNow().inWholeMilliseconds.coerceToInt()
        val ndjsonOk = validateNdjson(streamedContent.toString())
        val trueStreaming = streamSupported && contentChunkCount > 1 && lastContentAtMs - ttftMs >= 15
        if (streamSupported && !trueStreaming) {
            warnings += "接口接受 stream=true，但正文未呈现可观测的分时增量，可能在网关缓冲后集中返回。"
        }
        if (streamSupported && !ndjsonOk) warnings += "模型未严格遵循 NDJSON 输出约束。"

        val responseFormatSupported = probeResponseFormat(
            endpoint = endpoint,
            apiKey = apiKey,
            model = model,
            tokenParameter = workingTokenParameter,
        )
        if (!responseFormatSupported) warnings += "response_format=json_object 不可用，将继续依赖提示词约束和校验重试。"

        val normalizedModel = model.lowercase()
        val isReasoningModel = normalizedModel.startsWith("gpt-5") ||
            listOf("o1", "o3", "o4", "deepseek-v4-").any(normalizedModel::startsWith)
        val offProfile = mapOf(
            "model" to model,
            "base_url" to baseUrl,
            "reasoning_effort" to "off",
        )
        val (offThinking, offEffort) = resolveReasoningParams(offProfile)
        val reasoningOffSupported = if (!isReasoningModel) {
            true
        } else {
            probeReasoningOff(endpoint, apiKey, model, workingTokenParameter, offThinking, offEffort)
        }
        val reasoningOffStatus = when {
            !isReasoningModel -> "not_required"
            reasoningOffSupported -> "supported"
            else -> "unsupported"
        }
        if (isReasoningModel && !reasoningOffSupported) warnings += "接口未确认支持关闭推理，建议保持 auto。"

        val adherence = (if (ndjsonOk) 50 else 0) + (if (responseFormatSupported) 50 else 0)
        val recommendedMaxTokens = when {
            configuredMaxTokens in 256..16000 -> configuredMaxTokens
            isReasoningModel -> 8192
            else -> 4096
        }
        val averageChunkSize = chunkSizes.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0
        return ModelCapabilityReportDto(
            ok = streamSupported || responseFormatSupported,
            provider = provider,
            model = model,
            ttftMs = ttftMs,
            totalMs = totalMs,
            streamSupported = streamSupported,
            trueStreaming = trueStreaming,
            sseChunkCount = chunkSizes.size,
            sseChunkMinBytes = chunkSizes.minOrNull() ?: 0,
            sseChunkAvgBytes = averageChunkSize,
            sseChunkMaxBytes = chunkSizes.maxOrNull() ?: 0,
            jsonNdjsonAdherence = adherence,
            responseFormatSupported = responseFormatSupported,
            reasoningOffSupported = reasoningOffSupported,
            reasoningOffStatus = reasoningOffStatus,
            recommendedMaxTokens = recommendedMaxTokens,
            recommendedReasoningEffort = if (reasoningOffSupported) "off" else reasoningEffort.ifBlank { "auto" },
            recommendedTokenParameter = workingTokenParameter,
            recommendedResponseFormatMode = if (responseFormatSupported) "json_object" else "prompt_only",
            warnings = warnings.distinct(),
        )
    }

    private suspend fun probeTokenParameter(
        endpoint: String,
        apiKey: String,
        model: String,
        tokenParameter: String,
    ): Boolean = postCapabilityRequest(
        endpoint,
        apiKey,
        capabilityRequest(model, "Reply only OK.", tokenParameter, 16),
    ).first

    private suspend fun probeResponseFormat(
        endpoint: String,
        apiKey: String,
        model: String,
        tokenParameter: String,
    ): Boolean {
        val (ok, body) = postCapabilityRequest(
            endpoint,
            apiKey,
            capabilityRequest(
                model,
                "Return one JSON object with the boolean field ok. No markdown.",
                tokenParameter,
                64,
                responseFormat = ResponseFormat("json_object"),
            ),
        )
        if (!ok) return false
        val content = runCatching {
            json.decodeFromString<ChatCompletionResponse>(body).choices.firstOrNull()?.message?.content.orEmpty()
        }.getOrDefault("")
        return runCatching { json.parseToJsonElement(content).jsonObject }.isSuccess
    }

    private suspend fun probeReasoningOff(
        endpoint: String,
        apiKey: String,
        model: String,
        tokenParameter: String,
        thinking: JsonObject?,
        reasoningEffort: String?,
    ): Boolean = postCapabilityRequest(
        endpoint,
        apiKey,
        capabilityRequest(
            model = model,
            prompt = "Reply only OK.",
            tokenParameter = tokenParameter,
            maxTokens = 32,
            thinking = thinking,
            reasoningEffort = reasoningEffort,
        ),
    ).first

    private suspend fun postCapabilityRequest(
        endpoint: String,
        apiKey: String,
        request: ChatCompletionRequest,
    ): Pair<Boolean, String> = runCatching {
        val response = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(request)
        }
        val body = response.bodyAsText()
        response.status.isSuccess() to body
    }.getOrElse { false to it.message.orEmpty() }

    private fun capabilityRequest(
        model: String,
        prompt: String,
        tokenParameter: String,
        maxTokens: Int,
        stream: Boolean = false,
        responseFormat: ResponseFormat? = null,
        thinking: JsonObject? = null,
        reasoningEffort: String? = null,
    ) = ChatCompletionRequest(
        model = model,
        messages = listOf(ChatMessage("user", prompt)),
        temperature = null,
        max_tokens = maxTokens.takeIf { tokenParameter == "max_tokens" },
        maxCompletionTokens = maxTokens.takeIf { tokenParameter == "max_completion_tokens" },
        stream = stream,
        thinking = thinking,
        reasoningEffort = reasoningEffort,
        responseFormat = responseFormat,
    )

    private fun validateNdjson(value: String): Boolean {
        val lines = value.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        return lines.size == 2 && lines.all { line ->
            runCatching { json.parseToJsonElement(line).jsonObject }.isSuccess
        }
    }

    private fun Long.coerceToInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

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
    suspend fun chatCompletionStream(
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        model: String? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
        onReasoning: (String) -> Unit = {},
        enableReasoning: Boolean = false,
        requireJsonObject: Boolean = false,
    ): ChatCompletionResponse {
        val profile = getActiveProfile()
        val resolvedModel = model ?: profile["model"] ?: throw IllegalStateException("No model configured")
        val baseUrl = profile["base_url"] ?: "https://api.openai.com/v1"
        val profileId = profile["profile_id"] ?: "default"

        val apiKey = modelApiKeyService.getApiKey(profileId)
            ?: throw IllegalStateException("No API key configured for profile: $profileId")

        val (thinking, reasoningEffort) = resolveReasoningParams(profile)
        val tokenParameter = resolveTokenParameter(profile, resolvedModel)
        val request = ChatCompletionRequest(
            model = resolvedModel,
            messages = messages,
            temperature = temperature ?: DEFAULT_TEMPERATURE,
            max_tokens = maxTokens.takeIf { tokenParameter == "max_tokens" },
            maxCompletionTokens = maxTokens.takeIf { tokenParameter == "max_completion_tokens" },
            stream = true,
            thinking = thinking,
            reasoningEffort = reasoningEffort,
            responseFormat = nativeJsonResponseFormat(profile, resolvedModel, requireJsonObject),
        )

        val response = openStreamingHttpPost(
            url = "$baseUrl/chat/completions",
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = json.encodeToString(ChatCompletionRequest.serializer(), request),
        )
        try {
            if (response.statusCode !in 200..299) {
                throw modelApiError(response.statusCode, response.statusDescription, response.readRemainingText())
            }

            val contentBuilder = StringBuilder()
            var finishReason: String? = null
            var responseModel = resolvedModel
            while (true) {
                val data = sseData(response.readUtf8Line() ?: break) ?: continue
                if (data == "[DONE]") continue
                consumeDecodedSsePayload(
                    data = data,
                    decode = { json.decodeFromString<StreamChunk>(it) },
                    onDecodeFailure = { error -> PlatformLog.w(TAG, "Failed to parse SSE chunk: $data", error) },
                ) { chunk ->
                    responseModel = chunk.model.ifBlank { responseModel }
                    val delta = chunk.choices.firstOrNull()?.delta
                    delta?.content?.takeIf { it.isNotEmpty() }?.let {
                        contentBuilder.append(it)
                        onDelta(it)
                    }
                    delta?.reasoningContent?.takeIf { it.isNotEmpty() }?.let(onReasoning)
                    chunk.choices.firstOrNull()?.finishReason?.let { finishReason = it }
                }
            }

            return ChatCompletionResponse(
                model = responseModel,
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = contentBuilder.toString()),
                        finish_reason = finishReason,
                    )
                ),
                usage = null,
            )
        } finally {
            response.close()
        }
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
    fun chatCompletionStream(
        messages: List<ChatMessage>,
        model: String? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
        onReasoning: (suspend (String) -> Unit)? = null,
        enableReasoning: Boolean = false,
        requireJsonObject: Boolean = false,
    ): Flow<String> = flow {
        val profile = getActiveProfile()
        val resolvedModel = model ?: profile["model"] ?: throw IllegalStateException("No model configured")
        val baseUrl = profile["base_url"] ?: "https://api.openai.com/v1"
        val profileId = profile["profile_id"] ?: "default"

        val apiKey = modelApiKeyService.getApiKey(profileId)
            ?: throw IllegalStateException("No API key configured for profile: $profileId")

        val (thinking, reasoningEffort) = resolveReasoningParams(profile)
        val tokenParameter = resolveTokenParameter(profile, resolvedModel)
        val request = ChatCompletionRequest(
            model = resolvedModel,
            messages = messages,
            temperature = temperature ?: DEFAULT_TEMPERATURE,
            max_tokens = maxTokens.takeIf { tokenParameter == "max_tokens" },
            maxCompletionTokens = maxTokens.takeIf { tokenParameter == "max_completion_tokens" },
            stream = true,
            thinking = thinking,
            reasoningEffort = reasoningEffort,
            responseFormat = nativeJsonResponseFormat(profile, resolvedModel, requireJsonObject),
        )

        val response = openStreamingHttpPost(
            url = "$baseUrl/chat/completions",
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = json.encodeToString(ChatCompletionRequest.serializer(), request),
        )
        try {
            if (response.statusCode !in 200..299) {
                throw modelApiError(response.statusCode, response.statusDescription, response.readRemainingText())
            }
            while (true) {
                val data = sseData(response.readUtf8Line() ?: break) ?: continue
                if (data == "[DONE]") continue
                consumeDecodedSsePayload(
                    data = data,
                    decode = { json.decodeFromString<StreamChunk>(it) },
                    onDecodeFailure = { error -> PlatformLog.w(TAG, "Failed to parse SSE chunk: $data", error) },
                ) { chunk ->
                    val delta = chunk.choices.firstOrNull()?.delta
                    delta?.content?.takeIf { it.isNotEmpty() }?.let { emit(it) }
                    delta?.reasoningContent?.takeIf { it.isNotEmpty() }?.let { onReasoning?.invoke(it) }
                }
            }
        } finally {
            response.close()
        }
    }

    private fun sseData(line: String): String? {
        if (!line.startsWith("data:")) return null
        return line.substring(5).trimStart()
    }

    private fun modelApiError(statusCode: Int, statusDescription: String, body: String): IllegalStateException {
        val message = runCatching { json.decodeFromString<ErrorResponse>(body).error.message }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: body
        val status = listOf(statusCode.toString(), statusDescription.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return IllegalStateException("API error ($status): $message")
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

/**
 * Only decoding failures are recoverable for an individual SSE payload.
 * Exceptions raised by [consume] (for example a downstream Broken pipe) must propagate so a Flow
 * stops immediately instead of attempting more emissions from a failed collector.
 */
internal suspend fun <T> consumeDecodedSsePayload(
    data: String,
    decode: (String) -> T,
    onDecodeFailure: (Exception) -> Unit,
    consume: suspend (T) -> Unit,
): Boolean {
    val decoded = try {
        decode(data)
    } catch (error: Exception) {
        onDecodeFailure(error)
        return false
    }
    consume(decoded)
    return true
}

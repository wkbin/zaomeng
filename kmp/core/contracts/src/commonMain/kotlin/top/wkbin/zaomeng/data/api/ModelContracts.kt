package top.wkbin.zaomeng.data.api

import okio.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class ModelSettingsDto(
    val provider: String = "openai-compatible",
    val model: String = "",
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("max_tokens") val maxTokens: Int = 0,
    @SerialName("reasoning_effort") val reasoningEffort: String = "off",
    @SerialName("token_parameter") val tokenParameter: String = "auto",
    @SerialName("response_format_mode") val responseFormatMode: String = "auto",
    @SerialName("api_key_configured") val apiKeyConfigured: Boolean = false,
    val configured: Boolean = false,
    @SerialName("active_profile_id") val activeProfileId: String = "",
    val profiles: List<ModelProfileDto> = emptyList(),
)

@Serializable
data class ModelProfileDto(
    @SerialName("profile_id") val profileId: String = "",
    val name: String = "",
    val provider: String = "openai-compatible",
    val model: String = "",
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("max_tokens") val maxTokens: Int = 0,
    @SerialName("reasoning_effort") val reasoningEffort: String = "off",
    @SerialName("token_parameter") val tokenParameter: String = "auto",
    @SerialName("response_format_mode") val responseFormatMode: String = "auto",
    @SerialName("api_key_configured") val apiKeyConfigured: Boolean = false,
    val configured: Boolean = false,
)

@Serializable
data class SaveModelSettingsRequest(
    val provider: String,
    val model: String,
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("api_key") val apiKey: String = "",
    @SerialName("max_tokens") val maxTokens: Int = 0,
    @SerialName("reasoning_effort") val reasoningEffort: String = "off",
    @SerialName("token_parameter") val tokenParameter: String = "auto",
    @SerialName("response_format_mode") val responseFormatMode: String = "auto",
    @SerialName("profile_id") val profileId: String = "",
    @SerialName("profile_name") val profileName: String = "",
    @SerialName("create_profile") val createProfile: Boolean = false,
    @SerialName("activate_profile") val activateProfile: Boolean = true,
)

@Serializable
data class TestModelSettingsRequest(
    val provider: String,
    val model: String,
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("api_key") val apiKey: String = "",
    @SerialName("max_tokens") val maxTokens: Int = 0,
    @SerialName("reasoning_effort") val reasoningEffort: String = "off",
    @SerialName("token_parameter") val tokenParameter: String = "auto",
    @SerialName("response_format_mode") val responseFormatMode: String = "auto",
    @SerialName("profile_id") val profileId: String = "",
)

@Serializable
data class ModelConnectionTestDto(
    val ok: Boolean = false,
    val provider: String = "",
    val model: String = "",
    @SerialName("latency_ms") val latencyMs: Int = 0,
    val message: String = "",
)

@Serializable
data class ModelCapabilityReportDto(
    val ok: Boolean = false,
    val provider: String = "",
    val model: String = "",
    @SerialName("ttft_ms") val ttftMs: Int = 0,
    @SerialName("total_ms") val totalMs: Int = 0,
    @SerialName("stream_supported") val streamSupported: Boolean = false,
    @SerialName("true_streaming") val trueStreaming: Boolean = false,
    @SerialName("sse_chunk_count") val sseChunkCount: Int = 0,
    @SerialName("sse_chunk_min_bytes") val sseChunkMinBytes: Int = 0,
    @SerialName("sse_chunk_avg_bytes") val sseChunkAvgBytes: Int = 0,
    @SerialName("sse_chunk_max_bytes") val sseChunkMaxBytes: Int = 0,
    @SerialName("json_ndjson_adherence") val jsonNdjsonAdherence: Int = 0,
    @SerialName("response_format_supported") val responseFormatSupported: Boolean = false,
    @SerialName("reasoning_off_supported") val reasoningOffSupported: Boolean = false,
    @SerialName("reasoning_off_status") val reasoningOffStatus: String = "unknown",
    @SerialName("recommended_max_tokens") val recommendedMaxTokens: Int = 4096,
    @SerialName("recommended_reasoning_effort") val recommendedReasoningEffort: String = "off",
    @SerialName("recommended_token_parameter") val recommendedTokenParameter: String = "max_tokens",
    @SerialName("recommended_response_format_mode") val recommendedResponseFormatMode: String = "prompt_only",
    val warnings: List<String> = emptyList(),
)


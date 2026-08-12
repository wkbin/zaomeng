package top.wkbin.zaomeng.ktor.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject

/**
 * 运行清单数据结构
 *
 * 对应 Python 的 run_manifest.json；被 DataModelsCompatibilityTest 验证 snake_case 解码。
 */
@Serializable
data class RunManifest(
    @SerialName("run_id")
    val runId: String,
    @SerialName("novel_name")
    val novelName: String? = null,
    val characters: List<String>? = null,
    @SerialName("max_sentences")
    val maxSentences: Int? = null,
    @SerialName("max_chars")
    val maxChars: Int? = null,
    val title: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    val status: String? = null,
    val progress: Double? = null,
    val error: JsonObject? = null,
    @SerialName("artifact_index")
    val artifactIndex: JsonObject? = null,
    val metadata: JsonObject? = null,
)

/**
 * 模型设置
 */
@Serializable
data class ModelSettings(
    @SerialName("active_profile_id")
    val activeProfileId: String? = null,
    val profiles: List<ModelProfile> = emptyList()
)

/**
 * 模型配置文件
 */
@Serializable
data class ModelProfile(
    @SerialName("profile_id")
    val profileId: String? = null,
    @SerialName("profile_name")
    val profileName: String? = null,
    val provider: String? = null,
    val model: String? = null,
    @SerialName("base_url")
    val baseUrl: String? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null,
    @SerialName("token_parameter")
    val tokenParameter: String? = null,
    @SerialName("response_format_mode")
    val responseFormatMode: String? = null,
    @SerialName("api_key_configured")
    val apiKeyConfigured: Boolean? = null,
    val configured: Boolean? = null
)

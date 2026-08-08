package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.randomUuid
import top.wkbin.zaomeng.ktor.models.*
import kotlin.time.TimeSource

/**
 * 设置管理服务
 *
 * 对应 Python 的 WebRunService 中的设置管理功能
 */
class SettingsManagementService(
    private val storageService: StorageService,
    private val modelApiKeyService: ModelApiKeyService
) {
    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    /**
     * 获取模型设置
     */
    fun getModelSettings(): JsonObject {
        val settings = storageService.readModelSettings()
            ?: return emptyModelSettings()
        val activeProfile = settings.profiles.firstOrNull { it.profileId == settings.activeProfileId }

        return buildJsonObject {
            put("active_profile_id", settings.activeProfileId ?: "")
            putProfileFields(activeProfile)
            put("profiles", buildJsonArray {
                settings.profiles.forEach { profile ->
                    add(buildJsonObject {
                        put("profile_id", profile.profileId ?: "")
                        put("name", profile.profileName ?: "")
                        putProfileFields(profile)
                    })
                }
            })
        }
    }

    /**
     * 保存模型设置
     *
     * 对应 Python: run_service.save_model_settings()
     */
    fun saveModelSettings(
        provider: String,
        model: String,
        baseUrl: String = "",
        apiKey: String = "",
        maxTokens: Int = 0,
        reasoningEffort: String = "off",
        profileId: String = "",
        profileName: String = "",
        createProfile: Boolean = false,
        activateProfile: Boolean = true
    ): JsonObject {
        // 验证参数
        if (provider.isBlank()) {
            throw IllegalArgumentException("Provider cannot be blank")
        }
        if (model.isBlank()) {
            throw IllegalArgumentException("Model cannot be blank")
        }
        if (maxTokens !in 0..16000) {
            throw IllegalArgumentException("maxTokens must be between 0 and 16000")
        }
        if (profileName.length > 80) {
            throw IllegalArgumentException("Profile name too long (max 80 chars)")
        }

        // 读取现有设置
        val existingSettings = storageService.readModelSettings()
        val profiles = existingSettings?.profiles?.toMutableList() ?: mutableListOf()

        // 确定要使用的 profile ID
        val targetProfileId = when {
            createProfile -> generateId()
            profileId.isNotBlank() -> profileId
            profiles.isEmpty() -> generateId()
            else -> existingSettings?.activeProfileId ?: profiles.first().profileId ?: generateId()
        }

        // 创建或更新 profile
        val targetProfile = ModelProfile(
            profileId = targetProfileId,
            profileName = profileName.ifBlank { "Default" },
            provider = provider,
            model = model,
            baseUrl = baseUrl,
            maxTokens = maxTokens,
            reasoningEffort = reasoningEffort
        )

        // 更新 profiles 列表
        val profileIndex = profiles.indexOfFirst { it.profileId == targetProfileId }
        if (profileIndex >= 0) {
            profiles[profileIndex] = targetProfile
        } else {
            profiles.add(targetProfile)
        }

        // 保存 API 密钥到 Keystore
        if (apiKey.isNotBlank()) {
            try {
                modelApiKeyService.saveApiKey(targetProfileId, apiKey)
                PlatformLog.d(TAG, "Saved API key for profile: $targetProfileId")
            } catch (e: Exception) {
                PlatformLog.e(TAG, "Failed to save API key: ${e.message}")
            }
        }

        // 创建新的设置对象
        val newSettings = ModelSettings(
            activeProfileId = if (activateProfile) targetProfileId else existingSettings?.activeProfileId,
            profiles = profiles
        )

        // 写入设置
        storageService.writeModelSettings(newSettings)

        PlatformLog.d(TAG, "Saved model settings: provider=$provider, model=$model, profile=$targetProfileId")

        return getModelSettings()
    }

    fun activateProfile(profileId: String): JsonObject {
        val normalized = profileId.trim()
        val settings = storageService.readModelSettings()
            ?: throw NoSuchElementException("Profile not found: $normalized")
        if (settings.profiles.none { it.profileId == normalized }) {
            throw NoSuchElementException("Profile not found: $normalized")
        }
        storageService.writeModelSettings(settings.copy(activeProfileId = normalized))
        return getModelSettings()
    }

    fun deleteProfile(profileId: String): JsonObject {
        val normalized = profileId.trim()
        val settings = storageService.readModelSettings()
            ?: throw NoSuchElementException("Profile not found: $normalized")
        val remaining = settings.profiles.filterNot { it.profileId == normalized }
        if (remaining.size == settings.profiles.size) throw NoSuchElementException("Profile not found: $normalized")
        val nextActive = when {
            settings.activeProfileId != normalized -> settings.activeProfileId
            else -> remaining.firstOrNull()?.profileId
        }
        storageService.writeModelSettings(settings.copy(activeProfileId = nextActive, profiles = remaining))
        return getModelSettings()
    }

    /**
     * 测试模型设置
     *
     * 对应 Python: run_service.test_model_settings()
     */
    suspend fun testModelSettings(
        provider: String,
        model: String,
        baseUrl: String = "",
        apiKey: String = "",
        maxTokens: Int = 0,
        reasoningEffort: String = "off",
        profileId: String = ""
    ): JsonObject {
        // 验证参数
        if (provider.isBlank() || model.isBlank()) {
            throw IllegalArgumentException("Provider and model cannot be blank")
        }

        // 获取 API 密钥
        val effectiveProfileId = profileId.ifBlank {
            storageService.readModelSettings()?.activeProfileId.orEmpty()
        }
        val effectiveApiKey = apiKey.ifBlank { modelApiKeyService.getApiKey(effectiveProfileId) ?: "" }

        if (effectiveApiKey.isBlank()) {
            return testResult(false, provider, model, 0, "API key not found for profile: $effectiveProfileId")
        }

        val resolvedBaseUrl = baseUrl.ifBlank { "https://api.openai.com/v1" }
        val client = LlmClient(modelApiKeyService, storageService)
        val startedAt = TimeSource.Monotonic.markNow()
        val result = client.testConnection(resolvedBaseUrl, effectiveApiKey, model)
        val latencyMs = startedAt.elapsedNow().inWholeMilliseconds
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return result.fold(
            onSuccess = { testResult(true, provider, model, latencyMs, "Connection successful") },
            onFailure = { error -> testResult(false, provider, model, latencyMs, error.message ?: "Model connection failed") }
        )
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putProfileFields(profile: ModelProfile?) {
        val profileId = profile?.profileId.orEmpty()
        val keyConfigured = profileId.isNotBlank() && modelApiKeyService.hasApiKey(profileId)
        val configured = !profile?.provider.isNullOrBlank() && !profile?.model.isNullOrBlank() && keyConfigured
        put("provider", profile?.provider.orEmpty())
        put("model", profile?.model.orEmpty())
        put("base_url", profile?.baseUrl.orEmpty())
        put("max_tokens", profile?.maxTokens ?: 0)
        put("reasoning_effort", profile?.reasoningEffort ?: "off")
        put("api_key_configured", keyConfigured)
        put("configured", configured)
    }

    private fun emptyModelSettings() = buildJsonObject {
        put("active_profile_id", "")
        putProfileFields(null)
        put("profiles", buildJsonArray { })
    }

    private fun testResult(ok: Boolean, provider: String, model: String, latencyMs: Int, message: String) = buildJsonObject {
        put("ok", ok)
        put("provider", provider)
        put("model", model)
        put("latency_ms", latencyMs)
        put("message", message)
    }

    /**
     * 生成唯一 ID
     */
    private fun generateId(): String {
        return randomUuid().replace("-", "")
    }

    companion object {
        private const val TAG = "SettingsManagementService"
    }
}

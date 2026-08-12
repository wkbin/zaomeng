package top.wkbin.zaomeng.ktor.services

import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.SecureKeyValueStore
import top.wkbin.zaomeng.platform.SecureStoreNames

/**
 * Service for managing model API keys（Android 用 Keystore 加密存储，JVM 用本地存储）。
 */
class ModelApiKeyService(private val store: SecureKeyValueStore) {
    companion object {
        private const val TAG = "ModelApiKeyService"
    }

    /**
     * Get the API key for a specific profile.
     *
     * @param profileId The profile identifier (or "default" for default profile)
     * @return The decrypted API key, or null if not found
     */
    fun getApiKey(profileId: String = "default"): String? {
        return try {
            val secretName = SecureStoreNames.secretName(profileId)
            store.get(secretName)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to retrieve API key for profile $profileId", e)
            null
        }
    }

    /**
     * Save an API key for a specific profile.
     *
     * @param profileId The profile identifier
     * @param apiKey The API key to save
     */
    fun saveApiKey(profileId: String, apiKey: String) {
        try {
            store.put(SecureStoreNames.secretName(profileId), apiKey)
            PlatformLog.d(TAG, "Saved API key for profile $profileId")
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to save API key for profile $profileId", e)
            throw e
        }
    }

    /**
     * 删除某个档案的 API Key（删除档案时同步清理，避免密钥残留在安全存储中）。
     */
    fun deleteApiKey(profileId: String) {
        try {
            store.remove(SecureStoreNames.secretName(profileId))
            PlatformLog.d(TAG, "Deleted API key for profile $profileId")
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to delete API key for profile $profileId", e)
        }
    }

    /**
     * Get all API keys as a map of profile ID to API key.
     *
     * @return Map of profile IDs to their API keys
     */
    fun getAllApiKeys(): Map<String, String> {
        return try {
            store.entries().mapNotNull { (key, value) ->
                if (value.isBlank()) {
                    null
                } else {
                    // Convert secret name back to profile ID
                    val profileId = when {
                        key == "model_api_key" -> "default"
                        key.startsWith("model_api_key_") -> key.removePrefix("model_api_key_")
                        else -> key
                    }
                    profileId to value
                }
            }.toMap()
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to retrieve all API keys", e)
            emptyMap()
        }
    }

    /**
     * Check if an API key exists for a profile.
     *
     * @param profileId The profile identifier
     * @return true if an API key exists, false otherwise
     */
    fun hasApiKey(profileId: String = "default"): Boolean {
        return getApiKey(profileId) != null
    }
}

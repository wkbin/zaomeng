package top.wkbin.zaomeng.ktor.services

import android.content.Context
import android.util.Log
import org.json.JSONObject
import top.wkbin.zaomeng.backend.ModelApiKeyStore

/**
 * Service for managing model API keys from Android Keystore.
 *
 * This service provides access to encrypted API keys stored in Android's secure Keystore.
 * Keys are encrypted at rest and only decrypted when needed for API calls.
 */
class ModelApiKeyService(private val context: Context) {
    private val keyStore = ModelApiKeyStore(context)

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
            val json = keyStore.snapshotJson()
            val document = JSONObject(json)
            val secretName = ModelApiKeyStore.secretName(profileId)

            // The snapshot contains the actual secret names as keys
            document.optString(secretName)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve API key for profile $profileId", e)
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
            keyStore.saveForProfile(profileId, apiKey)
            Log.d(TAG, "Saved API key for profile $profileId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save API key for profile $profileId", e)
            throw e
        }
    }

    /**
     * Get all API keys as a map of profile ID to API key.
     *
     * @return Map of profile IDs to their API keys
     */
    fun getAllApiKeys(): Map<String, String> {
        return try {
            val json = keyStore.snapshotJson()
            val document = JSONObject(json)
            val result = mutableMapOf<String, String>()

            document.keys().forEach { key ->
                val value = document.optString(key)
                if (value.isNotBlank()) {
                    // Convert secret name back to profile ID
                    val profileId = when {
                        key == "model_api_key" -> "default"
                        key.startsWith("model_api_key_") -> key.removePrefix("model_api_key_")
                        else -> key
                    }
                    result[profileId] = value
                }
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve all API keys", e)
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

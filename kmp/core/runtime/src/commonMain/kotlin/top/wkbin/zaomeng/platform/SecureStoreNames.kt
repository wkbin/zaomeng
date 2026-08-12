package top.wkbin.zaomeng.platform

/** Shared secret-name rule for model API keys across Android Keystore and JVM storage. */
object SecureStoreNames {
    fun secretName(profileId: String): String {
        val normalized = profileId.trim().ifBlank { "default" }
        return "model_api_key_$normalized"
    }
}

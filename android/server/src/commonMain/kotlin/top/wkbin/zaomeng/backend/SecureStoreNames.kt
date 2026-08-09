package top.wkbin.zaomeng.backend

/**
 * API 密钥的存储名规则（跨平台共享；Android Keystore / JVM 存储统一使用）。
 *
 * 统一为 "model_api_key_<profileId>"，空/默认 profile 归一到 "default"。
 */
object SecureStoreNames {
    fun secretName(profileId: String): String {
        val normalized = profileId.trim().ifBlank { "default" }
        return "model_api_key_$normalized"
    }
}

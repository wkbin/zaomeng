package top.wkbin.zaomeng.backend

/**
 * API 密钥的存储名规则（跨平台共享；Android Keystore / JVM 存储统一使用）。
 *
 * default/空 profile 沿用旧版 "model_api_key"（迁移兼容），其余为 "model_api_key_<profileId>"。
 */
object SecureStoreNames {
    fun secretName(profileId: String): String =
        profileId.trim().takeIf { it.isNotEmpty() && it != "default" }
            ?.let { "model_api_key_$it" }
            ?: "model_api_key"
}

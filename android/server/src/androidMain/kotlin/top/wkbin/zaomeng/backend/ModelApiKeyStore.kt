package top.wkbin.zaomeng.backend

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import top.wkbin.zaomeng.platform.SecureKeyValueStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Keeps model API keys encrypted by a non-exportable Android Keystore key. */
class ModelApiKeyStore(context: Context) : SecureKeyValueStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun get(key: String): String? {
        val encrypted = preferences.getString(preferenceName(key), null) ?: return null
        return runCatching { decrypt(encrypted) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    override fun put(key: String, value: String) {
        val normalized = value.trim()
        if (normalized.isEmpty()) return
        preferences.edit { putString(preferenceName(key), encrypt(normalized)) }
    }

    override fun remove(key: String) {
        preferences.edit { remove(preferenceName(key)) }
    }

    override fun entries(): Map<String, String> = buildMap {
        preferences.all.forEach { (name, encrypted) ->
            if (!name.startsWith(SECRET_PREFIX) || encrypted !is String) return@forEach
            runCatching { decrypt(encrypted) }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?.let { put(name.removePrefix(SECRET_PREFIX), it) }
        }
    }

    @Synchronized
    fun snapshotJson(): String {
        val document = JSONObject()
        preferences.all.forEach { (name, encrypted) ->
            if (!name.startsWith(SECRET_PREFIX) || encrypted !is String) return@forEach
            runCatching { decrypt(encrypted) }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?.let { document.put(name.removePrefix(SECRET_PREFIX), it) }
        }
        return document.toString()
    }

    @Synchronized
    fun saveForProfile(profileId: String, apiKey: String) {
        put(secretName(profileId), apiKey)
    }

    @Synchronized
    fun deleteForProfile(profileId: String) {
        remove(secretName(profileId))
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2) { "Invalid encrypted model key." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun preferenceName(secretName: String) = "$SECRET_PREFIX$secretName"

    companion object {
        internal fun secretName(profileId: String): String = SecureStoreNames.secretName(profileId)

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "zaomeng_model_api_key"
        private const val PREFERENCES_NAME = "zaomeng_model_secrets"
        private const val SECRET_PREFIX = "secret."
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

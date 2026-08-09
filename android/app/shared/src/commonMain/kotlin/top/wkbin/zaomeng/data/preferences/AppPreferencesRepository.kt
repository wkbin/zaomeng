package top.wkbin.zaomeng.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okio.IOException

/** 消息字号连续缩放比例：1f 为标准大小。 */
const val CHAT_FONT_SCALE_MIN = 0.8f
const val CHAT_FONT_SCALE_MAX = 1.3f
const val CHAT_FONT_SCALE_DEFAULT = 1f

data class ChatDisplayPreferences(
    val fontSizeScale: Float = CHAT_FONT_SCALE_DEFAULT,
    val compactMode: Boolean = false,
    val showModelReasoning: Boolean = false,
    val backgroundImageUri: String = "",
    val backgroundOpacity: Float = 0.35f,
    val backgroundBlurRadius: Float = 0f,
)

data class AppPreferences(
    val defaultCharacters: String = "",
    val autoDistill: Boolean = true,
    val restoreLastLocation: Boolean = true,
    val lastRunId: String = "",
    val lastSessionId: String = "",
    val chatDisplay: ChatDisplayPreferences = ChatDisplayPreferences(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

/** 跨平台偏好仓库：全部平台统一走官方 KMP DataStore（Preferences）。 */
class AppPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<AppPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { values ->
            AppPreferences(
                defaultCharacters = values[KEY_DEFAULT_CHARACTERS].orEmpty(),
                autoDistill = values[KEY_AUTO_DISTILL] ?: true,
                restoreLastLocation = values[KEY_RESTORE_LAST_LOCATION] ?: true,
                lastRunId = values[KEY_LAST_RUN_ID].orEmpty(),
                lastSessionId = values[KEY_LAST_SESSION_ID].orEmpty(),
                chatDisplay = ChatDisplayPreferences(
                    fontSizeScale = chatFontScaleFrom(
                        values[KEY_CHAT_FONT_SCALE],
                        values[KEY_CHAT_FONT_SIZE],
                    ),
                    compactMode = values[KEY_CHAT_COMPACT_MODE] ?: false,
                    showModelReasoning = values[KEY_CHAT_SHOW_MODEL_REASONING] ?: false,
                    backgroundImageUri = values[KEY_CHAT_BACKGROUND_IMAGE_URI].orEmpty(),
                    backgroundOpacity = (values[KEY_CHAT_BACKGROUND_OPACITY] ?: 0.35f).coerceIn(0.1f, 1f),
                    backgroundBlurRadius = (values[KEY_CHAT_BACKGROUND_BLUR_RADIUS] ?: 0f).coerceIn(0f, 32f),
                ),
                themeMode = ThemeMode.fromStorageValue(values[KEY_THEME_MODE]),
            )
        }

    val chatDisplayPreferences: Flow<ChatDisplayPreferences> = preferences
        .map { it.chatDisplay }
        .distinctUntilChanged()

    val themeMode: Flow<ThemeMode> = preferences
        .map { it.themeMode }
        .distinctUntilChanged()

    suspend fun saveImportDefaults(characters: String, autoDistill: Boolean) {
        dataStore.edit { values ->
            values[KEY_DEFAULT_CHARACTERS] = characters
            values[KEY_AUTO_DISTILL] = autoDistill
        }
    }

    suspend fun rememberRun(runId: String) {
        val normalizedRunId = runId.trim()
        dataStore.edit { values ->
            if (normalizedRunId.isBlank()) {
                values.remove(KEY_LAST_RUN_ID)
            } else {
                values[KEY_LAST_RUN_ID] = normalizedRunId
            }
            values.remove(KEY_LAST_SESSION_ID)
        }
    }

    suspend fun rememberSession(runId: String, sessionId: String) {
        val normalizedRunId = runId.trim()
        val normalizedSessionId = sessionId.trim()
        if (normalizedRunId.isBlank() || normalizedSessionId.isBlank()) return
        dataStore.edit { values ->
            values[KEY_LAST_RUN_ID] = normalizedRunId
            values[KEY_LAST_SESSION_ID] = normalizedSessionId
        }
    }

    suspend fun forgetRun(runId: String) {
        val normalizedRunId = runId.trim()
        dataStore.edit { values ->
            if (values[KEY_LAST_RUN_ID] == normalizedRunId) {
                values.remove(KEY_LAST_RUN_ID)
                values.remove(KEY_LAST_SESSION_ID)
            }
        }
    }

    suspend fun forgetSession(runId: String, sessionId: String) {
        val normalizedRunId = runId.trim()
        val normalizedSessionId = sessionId.trim()
        dataStore.edit { values ->
            if (
                values[KEY_LAST_RUN_ID] == normalizedRunId &&
                values[KEY_LAST_SESSION_ID] == normalizedSessionId
            ) {
                values.remove(KEY_LAST_SESSION_ID)
            }
        }
    }

    suspend fun setChatFontSize(scale: Float) {
        dataStore.edit { values ->
            values[KEY_CHAT_FONT_SCALE] = scale.coerceIn(CHAT_FONT_SCALE_MIN, CHAT_FONT_SCALE_MAX)
        }
    }

    suspend fun setCompactChatMode(enabled: Boolean) {
        dataStore.edit { values -> values[KEY_CHAT_COMPACT_MODE] = enabled }
    }

    suspend fun setShowModelReasoning(enabled: Boolean) {
        dataStore.edit { values -> values[KEY_CHAT_SHOW_MODEL_REASONING] = enabled }
    }

    suspend fun setChatBackgroundImageUri(uri: String) {
        dataStore.edit { values ->
            if (uri.isBlank()) values.remove(KEY_CHAT_BACKGROUND_IMAGE_URI)
            else values[KEY_CHAT_BACKGROUND_IMAGE_URI] = uri
        }
    }

    suspend fun setChatBackgroundOpacity(opacity: Float) {
        dataStore.edit { values ->
            values[KEY_CHAT_BACKGROUND_OPACITY] = opacity.coerceIn(0.1f, 1f)
        }
    }

    suspend fun setChatBackgroundBlurRadius(radius: Float) {
        dataStore.edit { values ->
            values[KEY_CHAT_BACKGROUND_BLUR_RADIUS] = radius.coerceIn(0f, 32f)
        }
    }

    suspend fun saveChatDisplayPreferences(preferences: ChatDisplayPreferences) {
        dataStore.edit { values ->
            values[KEY_CHAT_FONT_SCALE] = preferences.fontSizeScale
                .coerceIn(CHAT_FONT_SCALE_MIN, CHAT_FONT_SCALE_MAX)
            values[KEY_CHAT_COMPACT_MODE] = preferences.compactMode
            values[KEY_CHAT_SHOW_MODEL_REASONING] = preferences.showModelReasoning
            if (preferences.backgroundImageUri.isBlank()) values.remove(KEY_CHAT_BACKGROUND_IMAGE_URI)
            else values[KEY_CHAT_BACKGROUND_IMAGE_URI] = preferences.backgroundImageUri
            values[KEY_CHAT_BACKGROUND_OPACITY] = preferences.backgroundOpacity.coerceIn(0.1f, 1f)
            values[KEY_CHAT_BACKGROUND_BLUR_RADIUS] = preferences.backgroundBlurRadius.coerceIn(0f, 32f)
        }
    }

    suspend fun setRestoreLastLocation(enabled: Boolean) {
        dataStore.edit { values -> values[KEY_RESTORE_LAST_LOCATION] = enabled }
    }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        dataStore.edit { values -> values[KEY_THEME_MODE] = themeMode.storageValue }
    }

    private companion object {
        val KEY_DEFAULT_CHARACTERS = stringPreferencesKey("default_characters")
        val KEY_AUTO_DISTILL = booleanPreferencesKey("auto_distill")
        val KEY_RESTORE_LAST_LOCATION = booleanPreferencesKey("restore_last_location")
        val KEY_LAST_RUN_ID = stringPreferencesKey("last_run_id")
        val KEY_LAST_SESSION_ID = stringPreferencesKey("last_session_id")
        val KEY_CHAT_FONT_SCALE = floatPreferencesKey("chat_font_scale")
        // 旧版枚举字号（small/standard/large）仅用于迁移读取
        val KEY_CHAT_FONT_SIZE = stringPreferencesKey("chat_font_size")
        val KEY_CHAT_COMPACT_MODE = booleanPreferencesKey("chat_compact_mode")
        val KEY_CHAT_SHOW_MODEL_REASONING = booleanPreferencesKey("chat_show_model_reasoning")
        val KEY_CHAT_BACKGROUND_IMAGE_URI = stringPreferencesKey("chat_background_image_uri")
        val KEY_CHAT_BACKGROUND_OPACITY = floatPreferencesKey("chat_background_opacity")
        val KEY_CHAT_BACKGROUND_BLUR_RADIUS = floatPreferencesKey("chat_background_blur_radius")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}

private fun chatFontScaleFrom(scale: Float?, legacy: String?): Float =
    (scale ?: legacyFontScale(legacy)).coerceIn(CHAT_FONT_SCALE_MIN, CHAT_FONT_SCALE_MAX)

private fun legacyFontScale(value: String?): Float = when (value) {
    "small" -> 0.9f
    "large" -> 1.15f
    else -> CHAT_FONT_SCALE_DEFAULT
}

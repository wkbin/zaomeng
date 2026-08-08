package top.wkbin.zaomeng.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class ChatFontSize(
    val storageValue: String,
    val scale: Float,
) {
    SMALL("small", 0.9f),
    STANDARD("standard", 1f),
    LARGE("large", 1.15f),
    ;

    companion object {
        fun fromStorageValue(value: String?): ChatFontSize =
            entries.firstOrNull { it.storageValue == value } ?: STANDARD
    }
}

enum class ThemeMode(val storageValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromStorageValue(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

data class ChatDisplayPreferences(
    val fontSize: ChatFontSize = ChatFontSize.STANDARD,
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

class AppPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<AppPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { values ->
            AppPreferences(
                defaultCharacters = values[DEFAULT_CHARACTERS].orEmpty(),
                autoDistill = values[AUTO_DISTILL] ?: true,
                restoreLastLocation = values[RESTORE_LAST_LOCATION] ?: true,
                lastRunId = values[LAST_RUN_ID].orEmpty(),
                lastSessionId = values[LAST_SESSION_ID].orEmpty(),
                chatDisplay = ChatDisplayPreferences(
                    fontSize = ChatFontSize.fromStorageValue(values[CHAT_FONT_SIZE]),
                    compactMode = values[CHAT_COMPACT_MODE] ?: false,
                    showModelReasoning = values[CHAT_SHOW_MODEL_REASONING] ?: false,
                    backgroundImageUri = values[CHAT_BACKGROUND_IMAGE_URI].orEmpty(),
                    backgroundOpacity = (values[CHAT_BACKGROUND_OPACITY] ?: 0.35f).coerceIn(0.1f, 1f),
                    backgroundBlurRadius = (values[CHAT_BACKGROUND_BLUR_RADIUS] ?: 0f).coerceIn(0f, 32f),
                ),
                themeMode = ThemeMode.fromStorageValue(values[THEME_MODE]),
            )
        }

    val chatDisplayPreferences: Flow<ChatDisplayPreferences> = preferences
        .map { preferences -> preferences.chatDisplay }
        .distinctUntilChanged()

    val themeMode: Flow<ThemeMode> = preferences
        .map { preferences -> preferences.themeMode }
        .distinctUntilChanged()

    suspend fun saveImportDefaults(characters: String, autoDistill: Boolean) {
        dataStore.edit { values ->
            values[DEFAULT_CHARACTERS] = characters
            values[AUTO_DISTILL] = autoDistill
        }
    }

    suspend fun rememberRun(runId: String) {
        val normalizedRunId = runId.trim()
        dataStore.edit { values ->
            if (normalizedRunId.isBlank()) {
                values.remove(LAST_RUN_ID)
            } else {
                values[LAST_RUN_ID] = normalizedRunId
            }
            values.remove(LAST_SESSION_ID)
        }
    }

    suspend fun rememberSession(runId: String, sessionId: String) {
        val normalizedRunId = runId.trim()
        val normalizedSessionId = sessionId.trim()
        if (normalizedRunId.isBlank() || normalizedSessionId.isBlank()) return
        dataStore.edit { values ->
            values[LAST_RUN_ID] = normalizedRunId
            values[LAST_SESSION_ID] = normalizedSessionId
        }
    }

    suspend fun clearLastSession() {
        dataStore.edit { it.remove(LAST_SESSION_ID) }
    }

    suspend fun clearLastLocation() {
        dataStore.edit { values ->
            values.remove(LAST_RUN_ID)
            values.remove(LAST_SESSION_ID)
        }
    }

    suspend fun forgetRun(runId: String) {
        val normalizedRunId = runId.trim()
        dataStore.edit { values ->
            if (values[LAST_RUN_ID] == normalizedRunId) {
                values.remove(LAST_RUN_ID)
                values.remove(LAST_SESSION_ID)
            }
        }
    }

    suspend fun forgetSession(runId: String, sessionId: String) {
        val normalizedRunId = runId.trim()
        val normalizedSessionId = sessionId.trim()
        dataStore.edit { values ->
            if (
                values[LAST_RUN_ID] == normalizedRunId &&
                values[LAST_SESSION_ID] == normalizedSessionId
            ) {
                values.remove(LAST_SESSION_ID)
            }
        }
    }

    suspend fun setChatFontSize(fontSize: ChatFontSize) {
        dataStore.edit { values -> values[CHAT_FONT_SIZE] = fontSize.storageValue }
    }

    suspend fun setCompactChatMode(enabled: Boolean) {
        dataStore.edit { values -> values[CHAT_COMPACT_MODE] = enabled }
    }

    suspend fun setShowModelReasoning(enabled: Boolean) {
        dataStore.edit { values -> values[CHAT_SHOW_MODEL_REASONING] = enabled }
    }

    suspend fun setChatBackgroundImageUri(uri: String) {
        dataStore.edit { values ->
            if (uri.isBlank()) values.remove(CHAT_BACKGROUND_IMAGE_URI)
            else values[CHAT_BACKGROUND_IMAGE_URI] = uri
        }
    }

    suspend fun setChatBackgroundOpacity(opacity: Float) {
        dataStore.edit { values ->
            values[CHAT_BACKGROUND_OPACITY] = opacity.coerceIn(0.1f, 1f)
        }
    }

    suspend fun setChatBackgroundBlurRadius(radius: Float) {
        dataStore.edit { values ->
            values[CHAT_BACKGROUND_BLUR_RADIUS] = radius.coerceIn(0f, 32f)
        }
    }

    suspend fun saveChatDisplayPreferences(preferences: ChatDisplayPreferences) {
        dataStore.edit { values ->
            values[CHAT_FONT_SIZE] = preferences.fontSize.storageValue
            values[CHAT_COMPACT_MODE] = preferences.compactMode
            values[CHAT_SHOW_MODEL_REASONING] = preferences.showModelReasoning
            if (preferences.backgroundImageUri.isBlank()) values.remove(CHAT_BACKGROUND_IMAGE_URI)
            else values[CHAT_BACKGROUND_IMAGE_URI] = preferences.backgroundImageUri
            values[CHAT_BACKGROUND_OPACITY] = preferences.backgroundOpacity.coerceIn(0.1f, 1f)
            values[CHAT_BACKGROUND_BLUR_RADIUS] = preferences.backgroundBlurRadius.coerceIn(0f, 32f)
        }
    }

    suspend fun setRestoreLastLocation(enabled: Boolean) {
        dataStore.edit { values -> values[RESTORE_LAST_LOCATION] = enabled }
    }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        dataStore.edit { values -> values[THEME_MODE] = themeMode.storageValue }
    }

    private companion object {
        val DEFAULT_CHARACTERS = stringPreferencesKey("default_characters")
        val AUTO_DISTILL = booleanPreferencesKey("auto_distill")
        val RESTORE_LAST_LOCATION = booleanPreferencesKey("restore_last_location")
        val LAST_RUN_ID = stringPreferencesKey("last_run_id")
        val LAST_SESSION_ID = stringPreferencesKey("last_session_id")
        val CHAT_FONT_SIZE = stringPreferencesKey("chat_font_size")
        val CHAT_COMPACT_MODE = booleanPreferencesKey("chat_compact_mode")
        val CHAT_SHOW_MODEL_REASONING = booleanPreferencesKey("chat_show_model_reasoning")
        val CHAT_BACKGROUND_IMAGE_URI = stringPreferencesKey("chat_background_image_uri")
        val CHAT_BACKGROUND_OPACITY = floatPreferencesKey("chat_background_opacity")
        val CHAT_BACKGROUND_BLUR_RADIUS = floatPreferencesKey("chat_background_blur_radius")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }
}


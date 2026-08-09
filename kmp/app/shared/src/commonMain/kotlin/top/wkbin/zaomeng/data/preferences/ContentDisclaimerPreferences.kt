package top.wkbin.zaomeng.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/** 内容免责声明偏好：与其余偏好统一存进同一个 KMP DataStore。 */
class ContentDisclaimerPreferences(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun isAccepted(): Boolean =
        dataStore.data.first()[KEY_ACCEPTED] ?: false

    suspend fun accept() {
        dataStore.edit { it[KEY_ACCEPTED] = true }
    }

    private companion object {
        val KEY_ACCEPTED = booleanPreferencesKey("accepted")
    }
}

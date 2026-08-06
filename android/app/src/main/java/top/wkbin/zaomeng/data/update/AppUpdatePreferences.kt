package top.wkbin.zaomeng.data.update

import android.content.Context
import androidx.core.content.edit

object AppUpdatePreferences {
    fun downloadPath(context: Context): String = preferences(context).getString(KEY_DOWNLOAD_PATH, "").orEmpty()

    fun downloadVersion(context: Context): String = preferences(context).getString(KEY_DOWNLOAD_VERSION, "").orEmpty()

    fun rememberDownload(context: Context, path: String, version: String) {
        preferences(context).edit {
            putString(KEY_DOWNLOAD_PATH, path).putString(
                KEY_DOWNLOAD_VERSION,
                version.trim()
            )
        }
    }

    fun clearDownload(context: Context) {
        preferences(context).edit { remove(KEY_DOWNLOAD_PATH).remove(KEY_DOWNLOAD_VERSION) }
    }

    fun isStartupCheckDisabled(context: Context): Boolean = preferences(context).getBoolean(KEY_DISABLE_STARTUP_CHECK, false)

    fun setStartupCheckDisabled(context: Context, disabled: Boolean) {
        preferences(context).edit { putBoolean(KEY_DISABLE_STARTUP_CHECK, disabled) }
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "app_update"
    private const val KEY_DOWNLOAD_PATH = "download_path"
    private const val KEY_DOWNLOAD_VERSION = "download_version"
    private const val KEY_DISABLE_STARTUP_CHECK = "disable_startup_check"
}

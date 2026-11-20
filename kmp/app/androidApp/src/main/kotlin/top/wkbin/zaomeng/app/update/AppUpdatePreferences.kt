package top.wkbin.zaomeng.app.update

import android.content.Context
import androidx.core.content.edit

/** 应用更新下载记录与启动检查开关（Android SharedPreferences）。 */
object AppUpdatePreferences {
    fun downloadPath(context: Context): String = preferences(context).getString(KEY_DOWNLOAD_PATH, "").orEmpty()

    fun downloadVersion(context: Context): String = preferences(context).getString(KEY_DOWNLOAD_VERSION, "").orEmpty()

    /** 下载完成时校验通过的摘要；用于下次启动时快速对账，避免重复哈希整个安装包。 */
    fun downloadDigest(context: Context): String = preferences(context).getString(KEY_DOWNLOAD_DIGEST, "").orEmpty()

    fun rememberDownload(context: Context, path: String, version: String, sha256: String = "") {
        preferences(context).edit {
            putString(KEY_DOWNLOAD_PATH, path)
                .putString(KEY_DOWNLOAD_VERSION, version.trim())
                .putString(KEY_DOWNLOAD_DIGEST, sha256.trim().lowercase())
        }
    }

    fun clearDownload(context: Context) {
        preferences(context).edit {
            remove(KEY_DOWNLOAD_PATH).remove(KEY_DOWNLOAD_VERSION).remove(KEY_DOWNLOAD_DIGEST)
        }
    }

    fun isStartupCheckDisabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_DISABLE_STARTUP_CHECK, false)

    fun setStartupCheckDisabled(context: Context, disabled: Boolean) {
        preferences(context).edit { putBoolean(KEY_DISABLE_STARTUP_CHECK, disabled) }
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "app_update"
    private const val KEY_DOWNLOAD_PATH = "download_path"
    private const val KEY_DOWNLOAD_VERSION = "download_version"
    private const val KEY_DOWNLOAD_DIGEST = "download_digest"
    private const val KEY_DISABLE_STARTUP_CHECK = "disable_startup_check"
}

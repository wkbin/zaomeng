package top.wkbin.zaomeng.data.preferences

import android.content.Context
import androidx.core.content.edit

object ContentDisclaimerPreferences {
    private const val PREFERENCES_NAME = "zaomeng_content_disclaimer"
    private const val KEY_ACCEPTED = "accepted"

    fun isAccepted(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ACCEPTED, false)

    fun accept(context: Context) {
        preferences(context).edit { putBoolean(KEY_ACCEPTED, true) }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}

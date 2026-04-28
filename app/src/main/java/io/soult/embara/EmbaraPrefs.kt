package io.soult.embara

import android.content.Context
import android.content.SharedPreferences

object EmbaraPrefs {

    private const val PREFS_NAME = "embara_prefs"
    private const val KEY_SERVER_URL = "server_url"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getServerUrl(context: Context): String? =
        prefs(context).getString(KEY_SERVER_URL, null)

    fun setServerUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun clearServerUrl(context: Context) {
        prefs(context).edit().remove(KEY_SERVER_URL).apply()
    }
}

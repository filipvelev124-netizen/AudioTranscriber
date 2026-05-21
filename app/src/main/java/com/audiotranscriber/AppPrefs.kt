package com.audiotranscriber

import android.content.Context

object AppPrefs {
    private const val PREFS      = "transcriber_prefs"
    private const val KEY_TOKEN  = "hf_api_token"

    fun getHfToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, "") ?: ""

    fun setHfToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token.trim()).apply()
    }
}

package com.audiotranscriber

import android.content.Context

object AppPrefs {
    private const val PREFS                 = "transcriber_prefs"
    private const val KEY_TOKEN             = "hf_api_token"
    private const val KEY_AUTO_COPY         = "auto_copy"
    private const val KEY_USE_CLOUD         = "use_cloud"
    private const val KEY_SENSITIVITY       = "sensitivity"       // 0–9
    private const val KEY_DISABLED_PACKAGES = "disabled_packages"

    // ── HF token ─────────────────────────────────────────────────────────────

    fun getHfToken(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "") ?: ""

    fun setHfToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    // ── Auto-copy ─────────────────────────────────────────────────────────────

    fun isAutoCopy(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_COPY, true)

    fun setAutoCopy(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_COPY, v).apply()
    }

    // ── Use cloud (Whisper) for all languages ─────────────────────────────────

    fun isUseCloud(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_CLOUD, false)

    fun setUseCloud(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_CLOUD, v).apply()
    }

    // ── Microphone sensitivity ────────────────────────────────────────────────
    // progress 0 (least sensitive, threshold 500) … 9 (most sensitive, threshold 50)
    // default 7 → threshold 150

    fun getSensitivity(context: Context): Int =
        prefs(context).getInt(KEY_SENSITIVITY, 7)

    fun setSensitivity(context: Context, v: Int) {
        prefs(context).edit().putInt(KEY_SENSITIVITY, v.coerceIn(0, 9)).apply()
    }

    fun getSilenceThreshold(context: Context): Double =
        ((10 - getSensitivity(context)) * 50).coerceAtLeast(50).toDouble()

    // ── Per-app filter ────────────────────────────────────────────────────────

    fun getDisabledPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DISABLED_PACKAGES, emptySet()) ?: emptySet()

    fun setDisabledPackages(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_DISABLED_PACKAGES, packages).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

package com.audiotranscriber

import android.content.Context

object AppPrefs {
    private const val PREFS                  = "transcriber_prefs"
    private const val KEY_AUTO_COPY          = "auto_copy"
    private const val KEY_SENSITIVITY        = "sensitivity"        // 0–9
    private const val KEY_DISABLED_PACKAGES  = "disabled_packages"
    private const val KEY_RECORDING_QUALITY  = "recording_quality"  // "Fast"|"Balanced"|"Best"

    // ── Auto-copy ─────────────────────────────────────────────────────────────

    fun isAutoCopy(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_COPY, true)

    fun setAutoCopy(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_COPY, v).apply()
    }

    // ── Microphone sensitivity ────────────────────────────────────────────────
    // progress 0 (least sensitive, threshold 200) … 9 (most sensitive, threshold 10)
    // default 9 → threshold 20 — matches quiet phone-speaker playback

    fun getSensitivity(context: Context): Int =
        prefs(context).getInt(KEY_SENSITIVITY, 9)

    fun setSensitivity(context: Context, v: Int) {
        prefs(context).edit().putInt(KEY_SENSITIVITY, v.coerceIn(0, 9)).apply()
    }

    fun getSilenceThreshold(context: Context): Double =
        ((10 - getSensitivity(context)) * 20).coerceAtLeast(10).toDouble()

    // ── Per-app filter ────────────────────────────────────────────────────────

    fun getDisabledPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DISABLED_PACKAGES, emptySet()) ?: emptySet()

    fun setDisabledPackages(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_DISABLED_PACKAGES, packages).apply()
    }

    // ── Recording quality ─────────────────────────────────────────────────────
    // Options: "Fast" | "Balanced" | "Best"

    fun getRecordingQuality(context: Context): String =
        prefs(context).getString(KEY_RECORDING_QUALITY, "Balanced") ?: "Balanced"

    fun setRecordingQuality(context: Context, quality: String) {
        prefs(context).edit().putString(KEY_RECORDING_QUALITY, quality).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

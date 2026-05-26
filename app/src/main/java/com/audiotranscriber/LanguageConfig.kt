package com.audiotranscriber

import android.content.Context

enum class Language(
    val displayName: String,
    val urls: List<String>,
    val isOnline: Boolean = false,
    val onlineLocale: String? = null,
    val whisperCode: String = "auto"
) {
    ENGLISH(    "English",              listOf(
                    "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                    "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.22.zip"),
                whisperCode = "en"),
    SPANISH(    "Spanish (Español)",    listOf("https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"),    whisperCode = "es"),
    FRENCH(     "French (Français)",    listOf("https://alphacephei.com/vosk/models/vosk-model-small-fr-pguyot-0.3.zip"), whisperCode = "fr"),
    GERMAN(     "German (Deutsch)",     listOf("https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip"),    whisperCode = "de"),
    RUSSIAN(    "Russian (Русский)",    listOf("https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"),    whisperCode = "ru"),
    PORTUGUESE( "Portuguese",           listOf("https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"),     whisperCode = "pt"),
    ITALIAN(    "Italian (Italiano)",   listOf("https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"),    whisperCode = "it"),
    CHINESE(    "Chinese (中文)",        listOf("https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"),    whisperCode = "zh"),
    HINDI(      "Hindi (हिन्दी)",        listOf("https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip"),    whisperCode = "hi"),
    DUTCH(      "Dutch (Nederlands)",   listOf("https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip"),    whisperCode = "nl"),
    TURKISH(    "Turkish (Türkçe)",     listOf("https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip"),     whisperCode = "tr"),
    KOREAN(     "Korean (한국어)",        listOf("https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip"),    whisperCode = "ko"),
    BULGARIAN(  "Bulgarian (Български)", emptyList(), whisperCode = "bg");

    companion object {
        private const val PREFS   = "transcriber_prefs"
        private const val KEY_SEL = "selected_language"

        fun getSelected(context: Context): Language {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SEL, ENGLISH.name) ?: ENGLISH.name
            return runCatching { valueOf(name) }.getOrDefault(ENGLISH)
        }

        fun setSelected(context: Context, language: Language) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SEL, language.name).apply()
        }
    }
}

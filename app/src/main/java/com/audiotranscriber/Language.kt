package com.audiotranscriber

enum class Language(
    val code: String,
    val displayName: String,
    val modelUrl: String,
    val approxSizeMb: Int
) {
    ENGLISH(
        code = "en",
        displayName = "English",
        modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.22.zip",
        approxSizeMb = 45
    ),
    BULGARIAN(
        code = "bg",
        displayName = "Bulgarian (Български)",
        modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-bg-0.22.zip",
        approxSizeMb = 48
    );

    companion object {
        fun fromCode(code: String): Language = entries.find { it.code == code } ?: ENGLISH
    }
}

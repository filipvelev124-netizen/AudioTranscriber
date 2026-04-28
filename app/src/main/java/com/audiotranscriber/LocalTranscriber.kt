package com.audiotranscriber

import android.content.Context
import org.vosk.Model
import org.vosk.Recognizer

object LocalTranscriber {

    private var model: Model? = null
    @Volatile var isReady = false
        private set

    fun initialize(context: Context, onReady: () -> Unit, onError: (String) -> Unit) {
        val modelPath = ModelDownloader.modelDir(context)
        if (!modelPath.exists()) {
            onError("Model not found — download it first")
            return
        }
        Thread {
            try {
                model = Model(modelPath.absolutePath)
                isReady = true
                onReady()
            } catch (e: Throwable) {
                // Throwable (not Exception) is required here — the Vosk JNI layer can throw
                // UnsatisfiedLinkError or OutOfMemoryError which extend Error, not Exception,
                // and would otherwise escape this handler and crash the entire process
                onError("Failed to load model: ${e.message}")
            }
        }.start()
    }

    fun createRecognizer(sampleRate: Float): Recognizer? {
        if (!isReady || model == null) return null
        return try { Recognizer(model, sampleRate) } catch (e: Throwable) { null }
    }

    // Vosk returns JSON: {"text": "hello world"}
    fun parseResult(json: String): String {
        return try {
            val text = json.substringAfter("\"text\" : \"").substringBefore("\"").trim()
            if (text.isEmpty()) "🔇 No speech detected" else text
        } catch (e: Throwable) { "❌ Could not parse result" }
    }

    // Vosk partial JSON: {"partial": "hel"}
    fun parsePartial(json: String): String {
        return try {
            json.substringAfter("\"partial\" : \"").substringBefore("\"").trim()
        } catch (e: Throwable) { "" }
    }
}

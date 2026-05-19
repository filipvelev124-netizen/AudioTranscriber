package com.audiotranscriber

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.vosk.Model
import org.vosk.Recognizer

object LocalTranscriber {

    @Volatile private var model: Model? = null
    @Volatile var isReady = false
        private set
    // Prevents a second background thread while the first is still loading.
    @Volatile private var isLoading = false

    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize(context: Context, onReady: () -> Unit, onError: (String) -> Unit) {
        if (isReady) { mainHandler.post { onReady() }; return }
        if (isLoading) return

        val modelPath = ModelDownloader.modelDir(context)
        if (!modelPath.exists()) {
            mainHandler.post { onError("Model not found — download it first") }
            return
        }

        // Validate required subdirectories before calling Model() — an incomplete
        // extraction causes a native C++ crash that cannot be caught by Java try-catch.
        if (!ModelDownloader.isModelValid(context)) {
            try { modelPath.deleteRecursively() } catch (_: Throwable) {}
            Language.clearDownloaded(context)
            mainHandler.post { onError("Model files are incomplete — please re-download in the app") }
            return
        }

        isLoading = true
        Thread {
            try {
                model = Model(modelPath.absolutePath)
                isReady = true
                isLoading = false
                mainHandler.post { onReady() }
            } catch (e: Throwable) {
                isLoading = false
                mainHandler.post { onError("Failed to load model: ${e.message}") }
            }
        }.apply { name = "vosk-model-loader" }.start()
    }

    fun reset() {
        isReady = false
        isLoading = false
        try { model?.close() } catch (_: Throwable) {}
        model = null
    }

    fun createRecognizer(sampleRate: Float): Recognizer? {
        if (!isReady || model == null) return null
        return try { Recognizer(model, sampleRate) } catch (e: Throwable) { null }
    }

    fun parseResult(json: String): String {
        return try {
            val text = json.substringAfter("\"text\" : \"").substringBefore("\"").trim()
            if (text.isEmpty()) "🔇 No speech detected" else text
        } catch (e: Throwable) { "❌ Could not parse result" }
    }

    fun parsePartial(json: String): String {
        return try {
            json.substringAfter("\"partial\" : \"").substringBefore("\"").trim()
        } catch (e: Throwable) { "" }
    }
}

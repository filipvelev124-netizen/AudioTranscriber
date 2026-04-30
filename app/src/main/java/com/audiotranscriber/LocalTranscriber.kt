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
    // Both MainActivity and the accessibility service call initialize() on startup
    // before isReady is true — without this guard they'd each start a thread,
    // load two Model objects simultaneously, and risk OOM on low-RAM devices.
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
        // extraction (e.g. process killed mid-unzip) leaves the directory present but
        // missing files. Passing such a path to Vosk causes a native C++ crash that
        // cannot be caught by any Java try-catch. Delete the corrupt dir so the
        // UI shows "not downloaded" and the user can re-download cleanly.
        if (!ModelDownloader.isModelValid(context)) {
            try { modelPath.deleteRecursively() } catch (_: Throwable) {}
            mainHandler.post { onError("Model files are incomplete — please re-download in the app") }
            return
        }

        isLoading = true
        Thread {
            try {
                model = Model(modelPath.absolutePath)
                isReady = true
                isLoading = false
                // Must dispatch to main thread. Callers (e.g. MainActivity) pass
                // UI-updating lambdas. Calling them from a background thread throws
                // CalledFromWrongThreadException; that exception escapes the catch block
                // and the JVM default uncaught handler kills the entire process.
                mainHandler.post { onReady() }
            } catch (e: Throwable) {
                // Throwable catches UnsatisfiedLinkError / OutOfMemoryError from Vosk JNI
                isLoading = false
                mainHandler.post { onError("Failed to load model: ${e.message}") }
            }
        }.apply { name = "vosk-model-loader" }.start()
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

package com.audiotranscriber

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

object WhisperEngine {

    private const val MODEL_FILENAME = "ggml-tiny.bin"
    const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
    private const val MIN_MODEL_SIZE = 10_000_000L

    @Volatile private var contextPtr: Long = 0L
    @Volatile var isReady: Boolean = false
        private set
    @Volatile private var isLoading = false

    private val mainHandler = Handler(Looper.getMainLooper())

    fun modelFile(context: Context): File = File(context.filesDir, MODEL_FILENAME)

    fun isModelDownloaded(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() >= MIN_MODEL_SIZE
    }

    @Synchronized
    fun initialize(context: Context, onReady: () -> Unit, onError: (String) -> Unit) {
        if (isReady) { mainHandler.post { onReady() }; return }
        if (isLoading) return
        isLoading = true

        val modelPath = modelFile(context).absolutePath
        Thread {
            try {
                System.loadLibrary("whisper_jni")
                val ptr = allocContext(modelPath)
                if (ptr == 0L) {
                    isLoading = false
                    mainHandler.post { onError("Failed to load Whisper model — try re-downloading") }
                    return@Thread
                }
                contextPtr = ptr
                isReady = true
                isLoading = false
                mainHandler.post { onReady() }
            } catch (e: Throwable) {
                isLoading = false
                mainHandler.post { onError(e.message ?: "Unknown error loading Whisper") }
            }
        }.apply { name = "whisper-loader" }.start()
    }

    @Synchronized
    fun transcribe(samples: FloatArray, language: Language): String {
        val ptr = contextPtr
        if (ptr == 0L) return ""
        return try { transcribeJni(ptr, samples, "auto") } catch (_: Throwable) { "" }
    }

    @Synchronized
    fun reset() {
        val ptr = contextPtr
        if (ptr != 0L) {
            try { freeContext(ptr) } catch (_: Throwable) {}
            contextPtr = 0L
        }
        isReady = false
        isLoading = false
    }

    private external fun allocContext(modelPath: String): Long
    private external fun freeContext(ctxPtr: Long)
    private external fun transcribeJni(ctxPtr: Long, samples: FloatArray, languageCode: String): String
}

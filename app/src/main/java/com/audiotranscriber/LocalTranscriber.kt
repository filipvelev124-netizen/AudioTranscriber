package com.audiotranscriber

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.InputStream

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
        // Load the model on a background thread — it reads ~50 MB from disk
        Thread {
            try {
                model = Model(modelPath.absolutePath)
                isReady = true
                onReady()
            } catch (e: Exception) {
                onError("Failed to load model: ${e.message}")
            }
        }.start()
    }

    suspend fun transcribeBytes(audioBytes: ByteArray, sampleRate: Float = 16_000f): String =
        withContext(Dispatchers.IO) {
            if (!isReady || model == null) return@withContext "⏳ Model not ready yet"
            runRecognizer(sampleRate) { recognizer ->
                var offset = 0
                val chunkSize = 4_096
                while (offset < audioBytes.size) {
                    val end = minOf(offset + chunkSize, audioBytes.size)
                    // acceptWaveForm(ByteArray, Int) — slice then pass size
                    val slice = audioBytes.copyOfRange(offset, end)
                    recognizer.acceptWaveForm(slice, slice.size)
                    offset += chunkSize
                }
            }
        }

    suspend fun transcribeFile(file: File, sampleRate: Float = 16_000f): String =
        withContext(Dispatchers.IO) {
            if (!isReady || model == null) return@withContext "⏳ Model not ready yet"
            runRecognizer(sampleRate) { recognizer ->
                file.inputStream().use { stream -> feedStream(recognizer, stream) }
            }
        }

    suspend fun transcribeStream(stream: InputStream, sampleRate: Float = 16_000f): String =
        withContext(Dispatchers.IO) {
            if (!isReady || model == null) return@withContext "⏳ Model not ready yet"
            runRecognizer(sampleRate) { recognizer -> feedStream(recognizer, stream) }
        }

    private fun feedStream(recognizer: Recognizer, stream: InputStream) {
        val buffer = ByteArray(4_096)
        var bytesRead: Int
        while (stream.read(buffer).also { bytesRead = it } != -1) {
            recognizer.acceptWaveForm(buffer, bytesRead)
        }
    }

    private fun runRecognizer(sampleRate: Float, feed: (Recognizer) -> Unit): String {
        return try {
            val recognizer = Recognizer(model, sampleRate)
            feed(recognizer)
            val result = recognizer.finalResult
            recognizer.close()
            parseResult(result)
        } catch (e: Exception) {
            "❌ Transcription error: ${e.message}"
        }
    }

    // Vosk returns JSON: {"text": "hello world"}
    private fun parseResult(json: String): String {
        return try {
            val text = json
                .substringAfter("\"text\" : \"")
                .substringBefore("\"")
                .trim()
            if (text.isEmpty()) "🔇 No speech detected" else text
        } catch (e: Exception) {
            "❌ Could not parse result"
        }
    }
}

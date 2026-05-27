package com.audiotranscriber

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class OnlineTranscriber(
    private val context: Context,
    private val locale: String,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                try { onError("Speech recognition not available on this device") } catch (_: Throwable) {}
                return@post
            }

            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return
                    try { onPartial(text) } catch (_: Throwable) {}
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    cleanup()
                    try { onResult(text) } catch (_: Throwable) {}
                }

                override fun onError(error: Int) {
                    cleanup()
                    try { onError(errorDescription(error)) } catch (_: Throwable) {}
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
            }

            try {
                recognizer?.startListening(intent)
            } catch (e: Throwable) {
                cleanup()
                try { onError("Could not start: ${e.message}") } catch (_: Throwable) {}
            }
        }
    }

    fun stop() {
        mainHandler.post {
            try { recognizer?.stopListening() } catch (_: Throwable) {}
        }
    }

    fun cancel() {
        mainHandler.post { cleanup() }
    }

    private fun cleanup() {
        try { recognizer?.cancel() } catch (_: Throwable) {}
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
    }

    private fun errorDescription(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT    -> "❌ No internet — check your connection"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT     -> "🔇 No speech detected — try again"
        SpeechRecognizer.ERROR_NO_MATCH           -> "🔇 Could not understand — try again"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY    -> "❌ Recognizer busy — wait a moment"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "❌ Microphone permission required"
        else                                       -> "❌ Recognition error ($error)"
    }
}

package com.audiotranscriber

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent

class OnlineTranscriber(
    private val context: Context,
    private val locale: String,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var restartCount = 0
    @Volatile private var cancelled = false
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        // Auto-restart on timeout: each session can be up to ~60s, 4 restarts = ~4 min max
        private const val MAX_RESTARTS = 4
    }

    fun start() {
        cancelled = false
        restartCount = 0
        startInternal()
    }

    private fun startInternal() {
        mainHandler.post {
            if (cancelled) return@post
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                try { onError("Speech recognition not available on this device") } catch (_: Throwable) {}
                return@post
            }

            try { recognizer?.cancel() } catch (_: Throwable) {}
            try { recognizer?.destroy() } catch (_: Throwable) {}
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(makeListener())

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Wait up to 6 s of silence before finalising — gives longer messages time to finish
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
                // Don't stop in the first 2 s even if silence is detected
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2_000L)
            }

            try {
                recognizer?.startListening(intent)
                // SpeechRecognizer steals AUDIOFOCUS_GAIN and pauses any playing media.
                // Dispatch a MEDIA_PLAY key event after it finishes initialising so the
                // voice message the user was already playing resumes automatically.
                mainHandler.postDelayed({ if (!cancelled) resumeMedia() }, 700)
            } catch (e: Throwable) {
                cleanup()
                try { onError("Could not start: ${e.message}") } catch (_: Throwable) {}
            }
        }
    }

    private fun resumeMedia() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        } catch (_: Throwable) {}
    }

    private fun makeListener() = object : RecognitionListener {
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
            restartCount = 0
            cleanup()
            try { onResult(text) } catch (_: Throwable) {}
        }

        override fun onError(error: Int) {
            if (cancelled) { cleanup(); return }
            // Session timed out or no match: voice message may still be playing — restart silently
            if ((error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)
                && restartCount < MAX_RESTARTS) {
                restartCount++
                try { recognizer?.cancel() } catch (_: Throwable) {}
                try { recognizer?.destroy() } catch (_: Throwable) {}
                recognizer = null
                startInternal()
                return
            }
            cleanup()
            try { onError(errorDescription(error)) } catch (_: Throwable) {}
        }
    }

    fun stop() {
        mainHandler.post {
            try { recognizer?.stopListening() } catch (_: Throwable) {}
        }
    }

    fun cancel() {
        cancelled = true
        mainHandler.post { cleanup() }
    }

    private fun cleanup() {
        try { recognizer?.cancel() } catch (_: Throwable) {}
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
    }

    private fun errorDescription(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT         -> "❌ No internet — check your connection"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT          -> "🔇 No speech detected — try again"
        SpeechRecognizer.ERROR_NO_MATCH                -> "🔇 Could not understand — try again"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY         -> "❌ Recognizer busy — wait a moment"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "❌ Microphone permission required"
        else                                            -> "❌ Recognition error ($error)"
    }
}

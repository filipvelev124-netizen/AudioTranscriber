package com.audiotranscriber

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class WhisperLocalTranscriber(
    private val context: Context,
    private val language: Language,
    private val silenceThreshold: Double,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        val record = buildMicCapture() ?: run {
            try { onError("❌ Microphone unavailable — check permission") } catch (_: Throwable) {}
            return
        }

        audioRecord = record
        record.startRecording()

        job = scope.launch {
            val samples   = mutableListOf<Short>()
            val buf       = ShortArray(4_096)
            val rmsWindow = ArrayDeque<Float>()
            val startMs   = System.currentTimeMillis()
            var lastLoud  = startMs
            var gotAudio  = false
            var lastNotif = 0L

            while (isActive) {
                val now     = System.currentTimeMillis()
                val elapsed = now - startMs
                if (elapsed > HARD_LIMIT_MS) break

                val read = record.read(buf, 0, buf.size)
                if (read <= 0) continue

                var sum = 0.0
                for (i in 0 until read) sum += buf[i].toDouble() * buf[i]
                val rms = sqrt(sum / read)
                if (rms > silenceThreshold) { gotAudio = true; lastLoud = now }

                if (!gotAudio && elapsed > STARTUP_TIMEOUT) {
                    releaseRecord()
                    withContext(Dispatchers.Main) {
                        try { onError("⏰ No audio detected — play the message louder or closer to the mic") } catch (_: Throwable) {}
                    }
                    return@launch
                }
                if (gotAudio && now - lastLoud > SILENCE_GAP_MS) break

                for (i in 0 until read) samples.add(buf[i])

                if (now - lastNotif > 400L) {
                    lastNotif = now
                    if (rmsWindow.size >= 8) rmsWindow.removeFirst()
                    rmsWindow.addLast(rms.toFloat())
                    val bar = rmsWindow.joinToString("") { r ->
                        when { r > 800 -> "█"; r > 400 -> "▆"; r > 200 -> "▄"; r > 100 -> "▂"; else -> "▁" }
                    }.ifEmpty { "▁▁▁▁▁▁▁▁" }
                    val secs = elapsed / 1_000
                    withContext(Dispatchers.Main) {
                        try { onPartial(if (gotAudio) "$bar  ${secs}s" else "▶ Play the voice message now…") } catch (_: Throwable) {}
                    }
                }
            }

            releaseRecord()
            if (!isActive) return@launch

            if (!gotAudio || samples.isEmpty()) {
                withContext(Dispatchers.Main) { try { onResult("") } catch (_: Throwable) {} }
                return@launch
            }

            withContext(Dispatchers.Main) {
                try { onPartial("⏳ Running Whisper offline…") } catch (_: Throwable) {}
            }

            try {
                val floats = FloatArray(samples.size) { samples[it] / 32768f }
                val text = WhisperEngine.transcribe(floats, language)
                withContext(Dispatchers.Main) {
                    try { onResult(text.trim()) } catch (_: Throwable) {}
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    try { onError("❌ ${e.message ?: "Transcription failed"}") } catch (_: Throwable) {}
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        releaseRecord()
    }

    private fun releaseRecord() {
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
    }

    private fun buildMicCapture(): AudioRecord? {
        val minBuf  = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = if (minBuf > 0) maxOf(minBuf * 4, 16_384) else 16_384
        return try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (_: Throwable) { null }
    }

    companion object {
        private const val SAMPLE_RATE     = 16_000
        private const val SILENCE_GAP_MS  = 2_000L
        private const val STARTUP_TIMEOUT = 15_000L
        private const val HARD_LIMIT_MS   = 90_000L
    }
}

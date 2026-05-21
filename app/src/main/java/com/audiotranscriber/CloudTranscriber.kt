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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

// Captures raw PCM via AudioRecord (no audio-focus conflict),
// converts to WAV, and sends to the free Hugging Face Whisper API.
// Works for any language — Whisper auto-detects from audio.
class CloudTranscriber(
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
        val token = AppPrefs.getHfToken(context)
        if (token.isBlank()) {
            try { onError("❌ Hugging Face token not set — enter it in the app first") } catch (_: Throwable) {}
            return
        }

        val record = buildMicCapture() ?: run {
            try { onError("❌ Microphone unavailable — check permission") } catch (_: Throwable) {}
            return
        }

        audioRecord = record
        record.startRecording()

        job = scope.launch {
            val samples    = mutableListOf<Short>()
            val buf        = ShortArray(4_096)
            val rmsWindow  = ArrayDeque<Float>()
            val startMs    = System.currentTimeMillis()
            var lastLoud   = startMs
            var gotAudio   = false
            var lastNotif  = 0L

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
                        when {
                            r > 800 -> "█"; r > 400 -> "▆"; r > 200 -> "▄"
                            r > 100 -> "▂"; else    -> "▁"
                        }
                    }
                    val secs = elapsed / 1_000
                    withContext(Dispatchers.Main) {
                        try { onPartial(if (gotAudio) "$bar  ${secs}s" else "▶ Play the voice message now…") } catch (_: Throwable) {}
                    }
                }
            }

            releaseRecord()
            if (!isActive) return@launch

            if (!gotAudio || samples.isEmpty()) {
                withContext(Dispatchers.Main) {
                    try { onResult("") } catch (_: Throwable) {}
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                try { onPartial("⏳ Sending to Whisper…") } catch (_: Throwable) {}
            }

            try {
                val wav  = samplesToWav(samples.toShortArray(), SAMPLE_RATE)
                val text = postToWhisper(wav, token)
                withContext(Dispatchers.Main) {
                    try { onResult(text) } catch (_: Throwable) {}
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

    private fun samplesToWav(samples: ShortArray, sampleRate: Int): ByteArray {
        val pcmBytes = samples.size * 2
        val out = ByteArrayOutputStream(44 + pcmBytes)
        fun w4(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF); out.write(v shr 16 and 0xFF); out.write(v shr 24 and 0xFF) }
        fun w2(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        out.write("RIFF".toByteArray()); w4(36 + pcmBytes); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); w4(16); w2(1); w2(1)
        w4(sampleRate); w4(sampleRate * 2); w2(2); w2(16)
        out.write("data".toByteArray()); w4(pcmBytes)
        for (s in samples) { out.write(s.toInt() and 0xFF); out.write(s.toInt() shr 8 and 0xFF) }
        return out.toByteArray()
    }

    private fun postToWhisper(wav: ByteArray, token: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val body    = wav.toRequestBody("audio/wav".toMediaType())
        val request = Request.Builder()
            .url("https://api-inference.huggingface.co/models/openai/whisper-large-v3-turbo")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val code     = response.code
        val raw      = response.body?.string().also { response.close() } ?: ""

        if (!response.isSuccessful) {
            throw IOException(when (code) {
                401, 403 -> "Invalid token — check your Hugging Face token"
                503      -> "Model loading on HF servers — wait 20 seconds and try again"
                429      -> "Rate limit reached — wait a moment and try again"
                else     -> "HTTP $code: $raw"
            })
        }

        return raw.substringAfter("\"text\":\"").trimStart().substringBefore("\"").trim()
    }

    companion object {
        private const val SAMPLE_RATE     = 16_000
        private const val SILENCE_GAP_MS  = 2_000L
        private const val STARTUP_TIMEOUT = 15_000L
        private const val HARD_LIMIT_MS   = 90_000L
    }
}

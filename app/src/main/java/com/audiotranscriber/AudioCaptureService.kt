package com.audiotranscriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class AudioCaptureService : Service() {

    companion object {
        const val ACTION_START_CAPTURE = "com.audiotranscriber.START_CAPTURE"
        const val ACTION_STOP_CAPTURE  = "com.audiotranscriber.STOP_CAPTURE"

        const val EXTRA_NODE_ID    = "node_id"
        const val EXTRA_TRANSCRIPT = "transcript"

        const val BROADCAST_RESULT = "com.audiotranscriber.TRANSCRIPT_RESULT"

        const val CHANNEL_ID      = "capture_channel"
        const val NOTIFICATION_ID = 42
        const val SAMPLE_RATE     = 16_000

        @Volatile var isRecording = false
            private set
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification("Open app to finish setup"))
        } catch (e: Throwable) { /* never crash on notification setup */ }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val nodeId = intent.getStringExtra(EXTRA_NODE_ID)
                    ?.takeIf { it.length <= 256 } ?: return START_STICKY
                startCapture(nodeId)
            }
            ACTION_STOP_CAPTURE -> stopCapture()
        }
        return START_STICKY
    }

    // ── Real-time microphone capture + Vosk streaming ─────────────────────────

    private fun startCapture(nodeId: String) {
        captureJob?.cancel()
        releaseAudioRecord()

        if (!LocalTranscriber.isReady) {
            broadcast(nodeId, "⏳ Model loading — wait a moment and try again")
            return
        }

        val recognizer = LocalTranscriber.createRecognizer(SAMPLE_RATE.toFloat()) ?: run {
            broadcast(nodeId, "⏳ Model not ready yet")
            return
        }

        val record = buildMicCapture() ?: run {
            recognizer.close()
            broadcast(nodeId, "❌ Microphone unavailable — open the app and grant microphone permission")
            return
        }

        audioRecord = record
        isRecording = true
        record.startRecording()
        updateNotification("🔴 Recording… play the message now")

        captureJob = scope.launch {
            try {
                val buf = ShortArray(4_096)
                val startMs           = System.currentTimeMillis()
                var lastLoudMs        = startMs
                var audioEverDetected = false
                var lastPartialMs     = 0L
                val accumulated       = StringBuilder()

                val silenceRmsThreshold = 150.0
                val silenceGapMs        = 2_000L
                val startupTimeoutMs    = 15_000L
                val hardLimitMs         = 90_000L
                val partialIntervalMs   = 300L

                while (isActive) {
                    val now     = System.currentTimeMillis()
                    val elapsed = now - startMs

                    if (elapsed > hardLimitMs) break

                    val read = record.read(buf, 0, buf.size)
                    if (read <= 0) continue

                    // RMS for silence detection
                    var sum = 0.0
                    for (i in 0 until read) sum += buf[i].toDouble() * buf[i]
                    val rms = sqrt(sum / read)

                    if (rms > silenceRmsThreshold) {
                        audioEverDetected = true
                        lastLoudMs = now
                    }

                    if (!audioEverDetected && elapsed > startupTimeoutMs) {
                        broadcast(nodeId, "⏰ No audio detected. Tap 🎙 then immediately play the voice message.")
                        break
                    }

                    if (audioEverDetected && (now - lastLoudMs) > silenceGapMs) break

                    // Feed PCM chunk to Vosk and stream partial results in real-time
                    try {
                        val bytes = ByteArray(read * 2)
                        for (i in 0 until read) {
                            bytes[i * 2]     = (buf[i].toInt() and 0xFF).toByte()
                            bytes[i * 2 + 1] = (buf[i].toInt() shr 8 and 0xFF).toByte()
                        }
                        if (recognizer.acceptWaveForm(bytes, bytes.size)) {
                            val text = LocalTranscriber.parseResult(recognizer.result)
                            if (!text.startsWith("🔇")) {
                                if (accumulated.isNotEmpty()) accumulated.append(" ")
                                accumulated.append(text)
                                broadcast(nodeId, accumulated.toString())
                            }
                        } else if (now - lastPartialMs > partialIntervalMs) {
                            lastPartialMs = now
                            val partial = LocalTranscriber.parsePartial(recognizer.partialResult)
                            if (partial.isNotEmpty()) {
                                val display = buildString {
                                    if (accumulated.isNotEmpty()) append(accumulated).append(" ")
                                    append("🎙 ").append(partial).append("…")
                                }
                                broadcast(nodeId, display)
                            }
                        }
                    } catch (e: Throwable) { /* Vosk feed error — skip chunk, keep recording */ }
                }

                if (!isActive) {
                    try { recognizer.close() } catch (_: Throwable) {}
                    return@launch
                }

                stopCapture()

                try {
                    val finalText = LocalTranscriber.parseResult(recognizer.finalResult)
                    recognizer.close()
                    val result = when {
                        !finalText.startsWith("🔇") && accumulated.isNotEmpty() -> "$accumulated $finalText"
                        !finalText.startsWith("🔇")                              -> finalText
                        accumulated.isNotEmpty()                                  -> accumulated.toString()
                        else                                                      -> "🔇 No speech detected"
                    }
                    broadcast(nodeId, result.trim())
                } catch (e: Throwable) {
                    try { recognizer.close() } catch (_: Throwable) {}
                    broadcast(nodeId, if (accumulated.isNotEmpty()) accumulated.toString() else "🔇 No speech detected")
                }

                updateNotification("Ready — tap 🎙 in any chat overlay")

            } catch (e: CancellationException) {
                // Coroutine cancelled (user tapped Stop, or service destroyed).
                // Must rethrow — swallowing CancellationException prevents the coroutine
                // from terminating and hangs the job indefinitely.
                try { recognizer.close() } catch (_: Throwable) {}
                throw e
            } catch (e: Throwable) {
                try { recognizer.close() } catch (_: Throwable) {}
                broadcast(nodeId, "❌ Capture error: ${e.message}")
                stopCapture()
            }
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        releaseAudioRecord()
        isRecording = false
        updateNotification("Ready — tap 🎙 in any chat overlay")
    }

    private fun releaseAudioRecord() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    private fun buildMicCapture(): AudioRecord? {
        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 4, 16_384
        )
        return try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (_: Exception) { null }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun broadcast(nodeId: String, transcript: String) {
        sendBroadcast(Intent(BROADCAST_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_NODE_ID, nodeId)
            putExtra(EXTRA_TRANSCRIPT, transcript)
        })
    }

    override fun onDestroy() {
        isRecording = false
        stopCapture()
        scope.cancel()
        super.onDestroy()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Audio Capture", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Transcriber")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }
}

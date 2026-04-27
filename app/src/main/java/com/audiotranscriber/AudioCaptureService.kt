package com.audiotranscriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
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
        // Intent actions
        const val ACTION_INIT_PROJECTION = "com.audiotranscriber.INIT_PROJECTION"
        const val ACTION_START_CAPTURE   = "com.audiotranscriber.START_CAPTURE"
        const val ACTION_STOP_CAPTURE    = "com.audiotranscriber.STOP_CAPTURE"

        // Intent extras
        const val EXTRA_RESULT_CODE      = "result_code"
        const val EXTRA_PROJECTION_DATA  = "projection_data"
        const val EXTRA_NODE_ID          = "node_id"

        // Broadcast actions sent back to the accessibility service
        const val BROADCAST_RESULT           = "com.audiotranscriber.TRANSCRIPT_RESULT"
        const val BROADCAST_PROJECTION_READY = "com.audiotranscriber.PROJECTION_READY"
        const val EXTRA_TRANSCRIPT           = "transcript"

        const val CHANNEL_ID      = "capture_channel"
        const val NOTIFICATION_ID = 42
        const val SAMPLE_RATE     = 16_000

        // Is the MediaProjection token currently held?
        @Volatile var isProjectionReady = false
            private set

        // Is the service actively recording right now?
        @Volatile var isRecording = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Ready"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INIT_PROJECTION -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                if (data != null) initProjection(code, data)
            }
            ACTION_START_CAPTURE -> {
                val nodeId = intent.getStringExtra(EXTRA_NODE_ID) ?: return START_STICKY
                startCapture(nodeId)
            }
            ACTION_STOP_CAPTURE -> stopCapture(sendEmptyResult = true)
        }
        return START_STICKY
    }

    // ── Projection init ───────────────────────────────────────────────────────

    private fun initProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection?.stop()
        mediaProjection = manager.getMediaProjection(resultCode, data)
        isProjectionReady = true
        sendBroadcast(Intent(BROADCAST_PROJECTION_READY))
        updateNotification("Ready — tap 🎙 in any chat overlay")
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    private fun startCapture(nodeId: String) {
        captureJob?.cancel()
        releaseAudioRecord()

        val record = buildAudioRecord() ?: run {
            broadcast(nodeId, "❌ Cannot open audio — check RECORD_AUDIO permission")
            return
        }
        audioRecord = record
        isRecording = true
        record.startRecording()
        updateNotification("🔴 Recording…  tap Stop in overlay to finish")

        captureJob = scope.launch {
            val pcm = mutableListOf<Byte>()
            val buf = ShortArray(4_096)
            val startMs = System.currentTimeMillis()
            var lastLoudMs = startMs
            var audioEverDetected = false
            val silenceRmsThreshold = 150.0   // below this = silence
            val silenceGapMs = 2_000L         // 2 s of silence → auto-stop
            val startupTimeoutMs = 15_000L    // 15 s to detect first audio
            val hardLimitMs = 90_000L         // absolute cap

            while (isActive) {
                val now = System.currentTimeMillis()
                val elapsed = now - startMs

                if (elapsed > hardLimitMs) break

                val read = record.read(buf, 0, buf.size)
                if (read <= 0) continue

                // RMS level
                var sum = 0.0
                for (i in 0 until read) sum += buf[i].toDouble() * buf[i]
                val rms = sqrt(sum / read)

                if (rms > silenceRmsThreshold) {
                    audioEverDetected = true
                    lastLoudMs = now
                }

                if (!audioEverDetected && elapsed > startupTimeoutMs) {
                    broadcast(nodeId, "⏰ No audio detected. Tap 🎙 then immediately tap play in the chat app.")
                    break
                }

                // 2 s of silence after audio was detected → done
                if (audioEverDetected && (now - lastLoudMs) > silenceGapMs) break

                // Store PCM samples as little-endian bytes
                for (i in 0 until read) {
                    val s = buf[i].toInt()
                    pcm.add((s and 0xFF).toByte())
                    pcm.add((s shr 8 and 0xFF).toByte())
                }
            }

            if (!isActive) return@launch  // cancelled by manual Stop

            stopCapture(sendEmptyResult = false)

            if (pcm.isEmpty()) {
                broadcast(nodeId, "🔇 No speech captured")
                return@launch
            }

            updateNotification("⏳ Transcribing…")
            val transcript = LocalTranscriber.transcribeBytes(pcm.toByteArray(), SAMPLE_RATE.toFloat())
            broadcast(nodeId, transcript)
            updateNotification("Ready — tap 🎙 in any chat overlay")
        }
    }

    fun stopCapture(sendEmptyResult: Boolean = false) {
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

    // ── AudioRecord factory ───────────────────────────────────────────────────

    private fun buildAudioRecord(): AudioRecord? {
        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 4, 16_384
        )

        // Android 10+ with a valid MediaProjection → capture system audio playback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            buildPlaybackCapture(bufSize)?.let { return it }
        }

        // Fallback: microphone (works when speaker is on)
        return buildMicCapture(bufSize)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildPlaybackCapture(bufSize: Int): AudioRecord? = try {
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .build()
        AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()
            .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    } catch (_: Exception) { null }

    private fun buildMicCapture(bufSize: Int): AudioRecord? = try {
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    } catch (_: Exception) { null }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun broadcast(nodeId: String, transcript: String) {
        sendBroadcast(Intent(BROADCAST_RESULT).apply {
            putExtra(EXTRA_NODE_ID, nodeId)
            putExtra(EXTRA_TRANSCRIPT, transcript)
        })
    }

    override fun onDestroy() {
        isProjectionReady = false
        isRecording = false
        stopCapture()
        mediaProjection?.stop()
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

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Transcriber")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}

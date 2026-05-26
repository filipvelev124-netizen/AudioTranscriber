package com.audiotranscriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class AudioCaptureService : Service() {

    companion object {
        const val ACTION_START_CAPTURE = "com.audiotranscriber.START_CAPTURE"
        const val ACTION_STOP_CAPTURE  = "com.audiotranscriber.STOP_CAPTURE"
        const val ACTION_COPY_RESULT   = "com.audiotranscriber.COPY_RESULT"
        const val ACTION_STOP_SERVICE  = "com.audiotranscriber.STOP_SERVICE"

        const val CHANNEL_ID      = "capture_channel"
        const val NOTIFICATION_ID = 42
        const val SAMPLE_RATE     = 16_000

        @Volatile var isRecording = false
            private set
        @Volatile var isRunning   = false
            private set

        private const val SILENCE_GAP_MS    = 2_000L
        private const val STARTUP_TIMEOUT   = 15_000L
        private const val HARD_LIMIT_MS     = 90_000L
        private const val NOTIF_THROTTLE_MS = 400L
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var stopCloudCapture: (() -> Unit)? = null
    private var stopWhisperCapture: (() -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastResult = ""
    private var copyReceiverRegistered = false

    private val copyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                if (lastResult.isEmpty()) return
                copyToClipboard(lastResult)
                notify(buildResultNotification(lastResult, copied = true))
            } catch (_: Throwable) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        isRunning = true
        if (!tryStartForeground()) { isRunning = false; stopSelf(); return }
        registerCopyReceiver()
    }

    private fun tryStartForeground(): Boolean {
        return try {
            createChannel()
            startForeground(NOTIFICATION_ID, buildIdleNotification())
            true
        } catch (_: Throwable) {
            try {
                startForeground(NOTIFICATION_ID,
                    NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("Audio Transcriber")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .build())
                true
            } catch (_: Throwable) { false }
        }
    }

    private fun registerCopyReceiver() {
        if (copyReceiverRegistered) return
        try {
            val filter = IntentFilter(ACTION_COPY_RESULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(copyReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(copyReceiver, filter)
            }
            copyReceiverRegistered = true
        } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> startCapture()
            ACTION_STOP_CAPTURE  -> stopCapture()
            ACTION_STOP_SERVICE  -> { stopCapture(); isRunning = false; stopSelf() }
        }
        return START_STICKY
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    private fun startCapture() {
        captureJob?.cancel()
        releaseAudioRecord()
        try { stopCloudCapture?.invoke() } catch (_: Throwable) {}
        stopCloudCapture = null
        try { stopWhisperCapture?.invoke() } catch (_: Throwable) {}
        stopWhisperCapture = null

        val language  = Language.getSelected(this)
        val threshold = AppPrefs.getSilenceThreshold(this)

        // Cloud path
        if (AppPrefs.isUseCloud(this)) {
            startCloudCapture(language, threshold)
            return
        }

        // Whisper offline path — one model covers all languages including Bulgarian
        if (WhisperEngine.isModelDownloaded(this)) {
            startWhisperCapture(language, threshold)
            return
        }

        // Bulgarian has no Vosk model; user needs Whisper or cloud
        if (language.isOnline) {
            notify(buildIdleNotification("⚠️ Enable cloud (⚙) or download Whisper model for ${language.displayName}"))
            return
        }

        // Local Vosk path
        if (!LocalTranscriber.isReady) {
            notify(buildIdleNotification("⏳ Loading model (first time, ~10 sec)…"))
            LocalTranscriber.initialize(
                context = this,
                onReady = { try { startCapture() } catch (_: Throwable) {} },
                onError = { _ -> try { notify(buildIdleNotification("❌ Model failed — re-download in the app")) } catch (_: Throwable) {} }
            )
            return
        }

        val recognizer = LocalTranscriber.createRecognizer(SAMPLE_RATE.toFloat()) ?: run {
            notify(buildIdleNotification("❌ Could not start recognizer"))
            return
        }

        val record = buildMicCapture() ?: run {
            recognizer.close()
            notify(buildIdleNotification("❌ Microphone unavailable — check permission in app"))
            return
        }

        audioRecord = record
        isRecording = true
        record.startRecording()
        notify(buildRecordingNotification("▶ Play the voice message now…"))

        captureJob = scope.launch {
            try {
                val buf         = ShortArray(4_096)
                val rmsWindow   = ArrayDeque<Float>()
                val startMs     = System.currentTimeMillis()
                var lastLoud    = startMs
                var gotAudio    = false
                var lastNotif   = 0L
                val accumulated = StringBuilder()

                while (isActive) {
                    val now     = System.currentTimeMillis()
                    val elapsed = now - startMs
                    if (elapsed > HARD_LIMIT_MS) break

                    val read = record.read(buf, 0, buf.size)
                    if (read <= 0) continue

                    var sum = 0.0
                    for (i in 0 until read) sum += buf[i].toDouble() * buf[i]
                    val rms = sqrt(sum / read)
                    if (rms > threshold) { gotAudio = true; lastLoud = now }

                    if (!gotAudio && elapsed > STARTUP_TIMEOUT) {
                        notify(buildIdleNotification("⏰ No audio — play the message louder or closer to the mic"))
                        break
                    }
                    if (gotAudio && (now - lastLoud) > SILENCE_GAP_MS) break

                    if (rmsWindow.size >= 8) rmsWindow.removeFirst()
                    rmsWindow.addLast(rms.toFloat())

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
                            }
                        }
                        if (now - lastNotif > NOTIF_THROTTLE_MS) {
                            lastNotif = now
                            val bar = rmsBar(rmsWindow)
                            val body = if (accumulated.isNotEmpty()) "$bar  $accumulated…" else "$bar  ${if (gotAudio) "Transcribing…" else "Play the voice message now…"}"
                            notify(buildRecordingNotification(body))
                        }
                    } catch (_: Throwable) {}
                }

                if (!isActive) { try { recognizer.close() } catch (_: Throwable) {}; return@launch }

                isRecording = false
                releaseAudioRecord()

                try {
                    val final = LocalTranscriber.parseResult(recognizer.finalResult)
                    try { recognizer.close() } catch (_: Throwable) {}
                    val result = when {
                        !final.startsWith("🔇") && accumulated.isNotEmpty() -> "$accumulated $final".trim()
                        !final.startsWith("🔇")                              -> final
                        accumulated.isNotEmpty()                              -> accumulated.toString()
                        else                                                  -> ""
                    }
                    withContext(Dispatchers.Main) { onTranscriptionComplete(result, language) }
                } catch (_: Throwable) {
                    try { recognizer.close() } catch (_: Throwable) {}
                    if (accumulated.isNotEmpty()) {
                        withContext(Dispatchers.Main) { onTranscriptionComplete(accumulated.toString(), language) }
                    } else {
                        try { notify(buildIdleNotification("🔇 No speech detected")) } catch (_: Throwable) {}
                    }
                }

            } catch (e: CancellationException) {
                try { recognizer.close() } catch (_: Throwable) {}
                throw e
            } catch (e: Throwable) {
                try { recognizer.close() } catch (_: Throwable) {}
                isRecording = false
                try { releaseAudioRecord() } catch (_: Throwable) {}
                try { notify(buildIdleNotification()) } catch (_: Throwable) {}
            }
        }
    }

    private fun startCloudCapture(language: Language, threshold: Double) {
        isRecording = true
        notify(buildRecordingNotification("▶ Play the voice message now…"))

        val transcriber = CloudTranscriber(
            context          = this,
            language         = language,
            silenceThreshold = threshold,
            onPartial = { partial ->
                try { notify(buildRecordingNotification(partial)) } catch (_: Throwable) {}
            },
            onResult  = { text ->
                isRecording = false
                stopCloudCapture = null
                onTranscriptionComplete(text, language)
            },
            onError   = { msg ->
                isRecording = false
                stopCloudCapture = null
                try { notify(buildIdleNotification(msg)) } catch (_: Throwable) {}
            }
        )
        stopCloudCapture = { transcriber.stop() }
        transcriber.start()
    }

    private fun startWhisperCapture(language: Language, threshold: Double) {
        if (!WhisperEngine.isReady) {
            notify(buildIdleNotification("⏳ Loading Whisper model (first time, ~5 sec)…"))
            WhisperEngine.initialize(
                context = this,
                onReady = { try { startCapture() } catch (_: Throwable) {} },
                onError = { e -> try { notify(buildIdleNotification("❌ $e")) } catch (_: Throwable) {} }
            )
            return
        }

        isRecording = true
        notify(buildRecordingNotification("▶ Play the voice message now…"))

        val transcriber = WhisperLocalTranscriber(
            context          = this,
            language         = language,
            silenceThreshold = threshold,
            onPartial = { partial -> try { notify(buildRecordingNotification(partial)) } catch (_: Throwable) {} },
            onResult  = { text ->
                isRecording = false
                stopWhisperCapture = null
                onTranscriptionComplete(text, language)
            },
            onError   = { msg ->
                isRecording = false
                stopWhisperCapture = null
                try { notify(buildIdleNotification(msg)) } catch (_: Throwable) {}
            }
        )
        stopWhisperCapture = { transcriber.stop() }
        transcriber.start()
    }

    // Called on the main thread after every successful transcription
    private fun onTranscriptionComplete(result: String, language: Language) {
        if (result.isEmpty()) {
            try { notify(buildIdleNotification("🔇 No speech detected — tap to try again")) } catch (_: Throwable) {}
            return
        }
        lastResult = result

        // Auto-copy if enabled
        val autoCopy = AppPrefs.isAutoCopy(this)
        if (autoCopy) copyToClipboard(result)

        // Persist to history
        scope.launch {
            try {
                AppDatabase.get(this@AudioCaptureService).transcriptDao().insert(
                    Transcript(timestamp = System.currentTimeMillis(), languageName = language.displayName, text = result)
                )
            } catch (_: Throwable) {}
        }

        try { notify(buildResultNotification(result, copied = autoCopy)) } catch (_: Throwable) {}
    }

    fun stopCapture() {
        try { captureJob?.cancel() } catch (_: Throwable) {}
        captureJob = null
        isRecording = false
        releaseAudioRecord()
        try { stopCloudCapture?.invoke() } catch (_: Throwable) {}
        stopCloudCapture = null
        try { stopWhisperCapture?.invoke() } catch (_: Throwable) {}
        stopWhisperCapture = null
        try { notify(buildIdleNotification()) } catch (_: Throwable) {}
    }

    private fun releaseAudioRecord() {
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
    }

    private fun copyToClipboard(text: String) {
        try {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("Transcript", text))
        } catch (_: Throwable) {}
    }

    private fun rmsBar(window: ArrayDeque<Float>): String =
        window.joinToString("") { r ->
            when {
                r > 800 -> "█"; r > 400 -> "▆"; r > 200 -> "▄"
                r > 100 -> "▂"; else    -> "▁"
            }
        }.ifEmpty { "▁▁▁▁▁▁▁▁" }

    private fun buildMicCapture(): AudioRecord? {
        val minBuf  = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = if (minBuf > 0) maxOf(minBuf * 4, 16_384) else 16_384
        return try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (_: Throwable) { null }
    }

    override fun onDestroy() {
        isRunning   = false
        isRecording = false
        try { captureJob?.cancel() } catch (_: Throwable) {}
        try { scope.cancel() } catch (_: Throwable) {}
        try { releaseAudioRecord() } catch (_: Throwable) {}
        try { stopCloudCapture?.invoke() } catch (_: Throwable) {}
        stopCloudCapture = null
        try { stopWhisperCapture?.invoke() } catch (_: Throwable) {}
        stopWhisperCapture = null
        if (copyReceiverRegistered) try { unregisterReceiver(copyReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun notify(n: Notification) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
        catch (_: Throwable) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Audio Transcriber", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun serviceAction(action: String, label: String): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, AudioCaptureService::class.java).apply { this.action = action },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action(0, label, pi)
    }

    private fun copyAction() = NotificationCompat.Action(0, "📋 Copy",
        PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_COPY_RESULT).apply { setPackage(packageName) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    private fun shareAction(): NotificationCompat.Action {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, lastResult)
        }
        val chooser = Intent.createChooser(sendIntent, "Share transcript").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            this, 1,
            chooser,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action(0, "↗ Share", pi)
    }

    private fun buildIdleNotification(text: String = "Tap 🎙 Transcribe, then play the voice message") =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Transcriber — Ready")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(serviceAction(ACTION_START_CAPTURE, "🎙 Transcribe"))
            .addAction(serviceAction(ACTION_STOP_SERVICE,  "✕ Stop service"))
            .build()

    private fun buildRecordingNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 Recording")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(serviceAction(ACTION_STOP_CAPTURE, "⏹ Stop"))
            .build()

    private fun buildResultNotification(result: String, copied: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (copied) "✅ Copied to clipboard" else "Transcription complete")
            .setContentText(result)
            .setStyle(NotificationCompat.BigTextStyle().bigText(result))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .apply {
                if (!copied) addAction(copyAction())   // already copied → skip
                addAction(shareAction())
                addAction(serviceAction(ACTION_START_CAPTURE, "🎙 Again"))
            }
            .build()
}

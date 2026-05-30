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

        const val CHANNEL_ID             = "capture_channel"
        const val RESULT_CHANNEL_ID      = "result_channel"
        const val NOTIFICATION_ID        = 42  // foreground service notification
        const val RESULT_NOTIFICATION_ID = 43  // result popup (separate from foreground)
        const val SAMPLE_RATE     = 16_000

        @Volatile var isRecording = false
            private set
        @Volatile var isRunning   = false
            private set

        private const val SILENCE_GAP_MS    = 1_500L   // stop 500ms sooner after speech ends
        private const val STARTUP_TIMEOUT   = 12_000L   // tell user sooner if nothing heard
        private const val HARD_LIMIT_MS     = 120_000L  // allow longer messages
        private const val NOTIF_THROTTLE_MS = 400L
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var onlineTranscriber: OnlineTranscriber? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureStartMs = 0L
    // Graceful stop flag: set by ACTION_STOP_CAPTURE so the Vosk loop exits naturally
    // and the accumulated text is processed instead of discarded.
    @Volatile private var stopRequested = false

    private var lastResult = ""
    private var copyReceiverRegistered = false

    private val copyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                if (lastResult.isEmpty()) return
                copyToClipboard(lastResult)
                notifyResult(buildResultNotification(lastResult, copied = true))
            } catch (_: Throwable) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        isRunning = true
        if (!tryStartForeground()) { isRunning = false; stopSelf(); return }
        registerCopyReceiver()
        prefetchAllModels()
    }

    // Download every undownloaded Vosk model silently in the background.
    // Downloads are sequential (ModelDownloader uses a shared temp dir).
    // Selected language goes first so it's ready when the user taps the mic.
    private fun prefetchAllModels() {
        scope.launch {
            val selected  = Language.getSelected(this@AudioCaptureService)
            val toFetch   = Language.values()
                .filter { !it.isOnline && !ModelDownloader.isDownloaded(this@AudioCaptureService, it) }
                .sortedBy { if (it == selected) 0 else 1 }
            for (lang in toFetch) {
                try {
                    ModelDownloader.download(
                        context    = this@AudioCaptureService,
                        language   = lang,
                        onProgress = {},
                        onComplete = {},
                        onError    = {}
                    )
                } catch (_: Throwable) {}
            }
        }
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
            ACTION_STOP_CAPTURE  -> stopCapture(saveResults = true)
            ACTION_STOP_SERVICE  -> { stopCapture(saveResults = false); isRunning = false; stopSelf() }
        }
        return START_STICKY
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    private fun startCapture() {
        cancelResultNotification()
        stopRequested = false
        captureJob?.cancel()
        releaseAudioRecord()
        try { onlineTranscriber?.cancel() } catch (_: Throwable) {}
        onlineTranscriber = null
        captureStartMs = 0L

        val language  = Language.getSelected(this)
        val threshold = AppPrefs.getSilenceThreshold(this)

        // Online languages (Bulgarian) use Android SpeechRecognizer — no model download needed
        if (language.isOnline) {
            startOnlineCapture(language)
            return
        }

        // Vosk model not yet downloaded → auto-download in background
        if (!ModelDownloader.isDownloaded(this, language)) {
            autoDownloadVosk(language)
            return
        }

        // Vosk model exists but not loaded yet
        if (!LocalTranscriber.isReady) {
            notify(buildIdleNotification("⏳ Loading model (first time, ~10 sec)…"))
            LocalTranscriber.initialize(
                context = this,
                onReady = { try { startCapture() } catch (_: Throwable) {} },
                onError = { _ -> try { notify(buildIdleNotification("❌ Model load failed — tap Transcribe to retry")) } catch (_: Throwable) {} }
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
        captureStartMs = System.currentTimeMillis()
        record.startRecording()
        notify(buildRecordingNotification("▶ Play the voice message now…"))

        captureJob = scope.launch {
            try {
                val buf         = ShortArray(4_096)
                val startMs     = System.currentTimeMillis()
                var lastLoud    = startMs
                var gotAudio    = false
                var lastNotif   = 0L
                val accumulated = StringBuilder()

                while (isActive && !stopRequested) {
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
                        notify(buildIdleNotification("⏰ No audio detected — play the message louder"))
                        break
                    }
                    if (gotAudio && (now - lastLoud) > SILENCE_GAP_MS) break

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
                            val body = when {
                                accumulated.isNotEmpty() -> "$accumulated…"
                                gotAudio                 -> "Transcribing…"
                                else                     -> "Play the voice message now…"
                            }
                            notify(buildRecordingNotification(body))
                        }
                    } catch (_: Throwable) {}
                }

                // Hard-cancelled (new capture started / service stopping) — discard results
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

    private fun autoDownloadVosk(language: Language) {
        notify(buildIdleNotification("⚙ Setting up ${language.displayName}…"))
        scope.launch {
            ModelDownloader.download(
                context  = this@AudioCaptureService,
                language = language,
                onProgress = { pct ->
                    val msg = if (pct < 0) "Installing ${language.displayName}…"
                              else "Downloading ${language.displayName}… $pct%"
                    try { notify(buildIdleNotification(msg)) } catch (_: Throwable) {}
                },
                onComplete = {
                    try { notify(buildIdleNotification("✅ ${language.displayName} ready — tap 🎙 Transcribe")) } catch (_: Throwable) {}
                },
                onError = { _ ->
                    try { notify(buildIdleNotification("❌ Setup failed — check internet and try again")) } catch (_: Throwable) {}
                }
            )
        }
    }

    private fun startOnlineCapture(language: Language) {
        isRecording = true
        captureStartMs = System.currentTimeMillis()
        notify(buildRecordingNotification("🌐 Listening (${language.displayName})… play the message now"))

        onlineTranscriber = OnlineTranscriber(
            context   = this,
            locale    = language.onlineLocale!!,
            onPartial = { partial ->
                try { notify(buildRecordingNotification("$partial…")) } catch (_: Throwable) {}
            },
            onResult  = { text ->
                isRecording = false
                onlineTranscriber = null
                onTranscriptionComplete(text, language)
            },
            onError   = { msg ->
                isRecording = false
                onlineTranscriber = null
                try { notify(buildIdleNotification(msg)) } catch (_: Throwable) {}
            }
        )
        onlineTranscriber?.start()
    }

    // Called on the main thread after every successful transcription
    private fun onTranscriptionComplete(result: String, language: Language) {
        if (result.isEmpty()) {
            try { notify(buildIdleNotification("🔇 No speech detected — tap to try again")) } catch (_: Throwable) {}
            return
        }
        lastResult = result
        val durationMs = if (captureStartMs > 0L) System.currentTimeMillis() - captureStartMs else 0L
        captureStartMs = 0L

        // Always copy to clipboard — result is ready for use
        val autoCopy = AppPrefs.isAutoCopy(this)
        copyToClipboard(result)

        // Persist to history
        scope.launch {
            try {
                AppDatabase.get(this@AudioCaptureService).transcriptDao().insert(
                    Transcript(
                        timestamp    = System.currentTimeMillis(),
                        languageName = language.displayName,
                        text         = result,
                        durationMs   = durationMs
                    )
                )
            } catch (_: Throwable) {}
        }

        // Post result as a separate popup notification (not the foreground one)
        try { notifyResult(buildResultNotification(result, copied = autoCopy)) } catch (_: Throwable) {}
        // Reset foreground notification back to idle/ready
        try { notify(buildIdleNotification()) } catch (_: Throwable) {}
    }

    fun stopCapture(saveResults: Boolean = false) {
        isRecording = false
        if (saveResults) {
            // Graceful stop: Vosk loop exits on the next iteration and runs the normal
            // finalResult → onTranscriptionComplete path (saves to history, copies clipboard).
            // Online path: stopListening() delivers partial results via onResult callback.
            stopRequested = true
            try { onlineTranscriber?.stop() } catch (_: Throwable) {}
            // captureJob stays alive until it finishes processing; don't release AudioRecord yet.
        } else {
            // Hard stop: discard everything (service dismissed / destroyed).
            stopRequested = false
            try { captureJob?.cancel() } catch (_: Throwable) {}
            captureJob = null
            releaseAudioRecord()
            try { onlineTranscriber?.cancel() } catch (_: Throwable) {}
            onlineTranscriber = null
            try { notify(buildIdleNotification()) } catch (_: Throwable) {}
        }
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

    private fun buildMicCapture(): AudioRecord? {
        val minBuf  = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = if (minBuf > 0) maxOf(minBuf * 4, 16_384) else 16_384
        // MIC source: raw audio, no echo-cancellation — VOICE_RECOGNITION has echo-cancellation
        // that actively filters out the phone speaker, which is exactly what we want to capture.
        return try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
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
        try { onlineTranscriber?.cancel() } catch (_: Throwable) {}
        onlineTranscriber = null
        if (copyReceiverRegistered) try { unregisterReceiver(copyReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun notify(n: Notification) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
        catch (_: Throwable) {}
    }

    private fun notifyResult(n: Notification) {
        try { getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, n) }
        catch (_: Throwable) {}
    }

    private fun cancelResultNotification() {
        try { getSystemService(NotificationManager::class.java).cancel(RESULT_NOTIFICATION_ID) }
        catch (_: Throwable) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Audio Transcriber", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
            nm.createNotificationChannel(
                NotificationChannel(RESULT_CHANNEL_ID, "Transcription Results", NotificationManager.IMPORTANCE_HIGH)
                    .apply { setShowBadge(true) }
            )
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
            .setContentTitle("🔴 Recording — play the voice message now")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(serviceAction(ACTION_STOP_CAPTURE, "⏹ Stop"))
            .build()

    private fun buildResultNotification(result: String, copied: Boolean) =
        NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle(if (copied) "✅ Copied to clipboard" else "Transcription complete")
            .setContentText(result)
            .setStyle(NotificationCompat.BigTextStyle().bigText(result))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent())
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(copyAction())
            .addAction(shareAction())
            .addAction(serviceAction(ACTION_START_CAPTURE, "🎙 Again"))
            .build()
}

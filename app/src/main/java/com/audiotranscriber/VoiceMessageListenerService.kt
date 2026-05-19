package com.audiotranscriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

class VoiceMessageListenerService : NotificationListenerService() {

    private val notifManager by lazy { getSystemService(NotificationManager::class.java) }
    private var lastPromptMs = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName == packageName) return
            if (sbn.packageName !in MESSAGING_PACKAGES) return

            val now = System.currentTimeMillis()
            if (now - lastPromptMs < DEBOUNCE_MS) return

            val extras = sbn.notification?.extras ?: return
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text  = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

            if (audioKeywords.any { ("$title $text").lowercase().contains(it) }) {
                lastPromptMs = now
                showTranscribePrompt(sbn.packageName)
            }
        } catch (_: Throwable) {}
    }

    private fun showTranscribePrompt(fromPackage: String) {
        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(fromPackage, 0)
            ).toString()
        } catch (_: Throwable) { "a chat app" }

        val transcribeIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this, 0,
                Intent(this, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_START_CAPTURE
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getService(
                this, 0,
                Intent(this, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_START_CAPTURE
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice message in $appLabel")
            .setContentText("Tap Transcribe, then immediately play the message")
            .setSmallIcon(R.drawable.ic_mic)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .addAction(0, "🎙 Transcribe", transcribeIntent)
            .build()

        try { notifManager.notify(PROMPT_NOTIF_ID, n) } catch (_: Throwable) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Voice Message Alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Banner when a voice message is detected in a chat app" }
            notifManager.createNotificationChannel(ch)
        }
    }

    companion object {
        private const val CHANNEL_ID    = "voice_alert_channel"
        const val PROMPT_NOTIF_ID       = 43
        private const val DEBOUNCE_MS   = 10_000L

        private val MESSAGING_PACKAGES = setOf(
            "com.whatsapp", "com.whatsapp.w4b",
            "org.telegram.messenger", "org.telegram.plus",
            "com.instagram.android",
            "com.facebook.orca",
            "com.viber.voip",
            "com.discord",
            "com.snapchat.android",
            "org.thoughtcrime.securesms",
            "im.vector.app",
            "com.skype.raider",
            "com.microsoft.teams",
        )

        private val audioKeywords = listOf(
            "voice message", "audio message", "voice note", "audio note",
            "voice memo", "audio clip", "voice clip",
            "sound message", "audio recording", "voice recording",
            "🎵", "🎙", "🔊"
        )
    }
}

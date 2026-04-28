package com.audiotranscriber

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

class TranscriberAccessibilityService : AccessibilityService() {

    private val overlayManager by lazy { OverlayManager(this) }

    private val processedNodes = mutableSetOf<String>()
    private var lastWindowId = -1
    private var receiversRegistered = false

    private val transcriptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                val nodeId = intent.getStringExtra(AudioCaptureService.EXTRA_NODE_ID)
                    ?.takeIf { it.length <= 256 } ?: return
                val transcript = intent.getStringExtra(AudioCaptureService.EXTRA_TRANSCRIPT)
                    ?.take(8_192) ?: return
                overlayManager.updateTranscript(nodeId, transcript)
            } catch (e: Throwable) { }
        }
    }

    override fun onServiceConnected() {
        // Promote this service to foreground so MIUI/aggressive ROMs cannot kill the process.
        // We do this on the accessibility service itself (not AudioCaptureService) because
        // accessibility services are bound by the system and don't need a foregroundServiceType
        // declaration — avoiding the Android 14 requirement that typed foreground services
        // hold the matching dangerous permission before starting.
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Throwable) { }

        try {
            registerReceivers()
        } catch (e: Throwable) { }

        try {
            LocalTranscriber.initialize(context = this, onReady = {}, onError = {})
        } catch (e: Throwable) { }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter(AudioCaptureService.BROADCAST_RESULT)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(transcriptReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(transcriptReceiver, filter)
            }
            receiversRegistered = true
        } catch (e: Throwable) {
            try {
                registerReceiver(transcriptReceiver, filter)
                receiversRegistered = true
            } catch (e2: Throwable) { }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            if (event.windowId != lastWindowId) {
                lastWindowId = event.windowId
                processedNodes.clear()
            }
            val root = rootInActiveWindow ?: return
            try {
                scanForAudioMessages(root)
            } finally {
                try { root.recycle() } catch (e: Throwable) { }
            }
        } catch (e: Throwable) { }
    }

    override fun onInterrupt() {
        try { overlayManager.removeAllOverlays() } catch (e: Throwable) { }
    }

    override fun onDestroy() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Throwable) { }
        try { if (receiversRegistered) unregisterReceiver(transcriptReceiver) } catch (e: Throwable) { }
        try { overlayManager.removeAllOverlays() } catch (e: Throwable) { }
        super.onDestroy()
    }

    // ── Tree traversal ────────────────────────────────────────────────────────

    private fun scanForAudioMessages(root: AccessibilityNodeInfo) {
        traverseTree(root) { node ->
            try {
                if (isAudioMessageNode(node)) {
                    val key = nodeKey(node)
                    if (key !in processedNodes) {
                        processedNodes.add(key)
                        showOverlayForNode(node, key)
                    }
                }
            } catch (e: Throwable) { }
        }
    }

    private fun traverseTree(node: AccessibilityNodeInfo, visitor: (AccessibilityNodeInfo) -> Unit) {
        try { visitor(node) } catch (e: Throwable) { }
        val count = try { node.childCount } catch (e: Throwable) { 0 }
        for (i in 0 until count) {
            val child = try { node.getChild(i) } catch (e: Throwable) { null } ?: continue
            try {
                traverseTree(child, visitor)
            } finally {
                try { child.recycle() } catch (e: Throwable) { }
            }
        }
    }

    // ── Audio node detection ──────────────────────────────────────────────────

    private fun isAudioMessageNode(node: AccessibilityNodeInfo): Boolean {
        val desc  = node.contentDescription?.toString()?.lowercase() ?: ""
        val text  = node.text?.toString()?.lowercase() ?: ""
        val clazz = node.className?.toString() ?: ""

        val isControl = node.isClickable ||
                clazz.contains("Button", ignoreCase = true) ||
                clazz.contains("ImageView", ignoreCase = true)

        if (!isControl) return false
        return audioKeywords.any { ("$desc $text").contains(it) }
    }

    // ── Overlay wiring ────────────────────────────────────────────────────────

    private fun showOverlayForNode(node: AccessibilityNodeInfo, nodeId: String) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        overlayManager.showTranscriptOverlay(
            x = rect.left,
            y = rect.bottom + 6,
            nodeId = nodeId,
            onTranscribeClick = { onTranscribeRequested(nodeId) },
            onStopClick = { onStopRequested(nodeId) }
        )
    }

    private fun onTranscribeRequested(nodeId: String) {
        if (!LocalTranscriber.isReady) {
            overlayManager.updateTranscript(nodeId, "⏳ Model loading — wait a moment and try again")
            return
        }
        overlayManager.setRecordingState(nodeId)
        try {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START_CAPTURE
                putExtra(AudioCaptureService.EXTRA_NODE_ID, nodeId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (e: Throwable) {
            overlayManager.updateTranscript(nodeId, "❌ Could not start capture: ${e.message}")
        }
    }

    private fun onStopRequested(nodeId: String) {
        overlayManager.setTranscribingState(nodeId)
        try {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_STOP_CAPTURE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (e: Throwable) { }
    }

    private fun nodeKey(node: AccessibilityNodeInfo): String {
        val r = Rect()
        node.getBoundsInScreen(r)
        return "${node.className}@${r.left},${r.top},${r.right},${r.bottom}"
    }

    // ── Foreground notification (keeps process alive on MIUI) ─────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Transcriber Service", NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Transcriber")
            .setContentText("Watching for voice messages…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID      = "service_channel"
        private const val NOTIFICATION_ID = 41

        private val audioKeywords = listOf(
            "voice message", "audio message", "voice note", "audio note",
            "play audio", "play voice", "play message", "audio clip",
            "voice clip", "voice recording", "sound message",
            "vocal message", "audio recording"
        )
    }
}

package com.audiotranscriber

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TranscriberAccessibilityService : AccessibilityService() {

    private lateinit var overlayManager: OverlayManager

    // Tracks node keys already given an overlay in the current window
    private val processedNodes = mutableSetOf<String>()
    private var lastWindowId = -1

    // Receives TRANSCRIPT_RESULT from AudioCaptureService
    private val transcriptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val nodeId    = intent.getStringExtra(AudioCaptureService.EXTRA_NODE_ID) ?: return
            val transcript = intent.getStringExtra(AudioCaptureService.EXTRA_TRANSCRIPT) ?: return
            overlayManager.updateTranscript(nodeId, transcript)
        }
    }

    // Receives PROJECTION_READY — retry pending captures if any
    private val projectionReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Nothing needed: the next tap will succeed automatically
        }
    }

    override fun onServiceConnected() {
        overlayManager = OverlayManager(this)

        val filter = IntentFilter(AudioCaptureService.BROADCAST_RESULT)
        val projFilter = IntentFilter(AudioCaptureService.BROADCAST_PROJECTION_READY)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(transcriptReceiver, filter, RECEIVER_NOT_EXPORTED)
            registerReceiver(projectionReadyReceiver, projFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(transcriptReceiver, filter)
            registerReceiver(projectionReadyReceiver, projFilter)
        }

        // Initialize Vosk model if model is already downloaded
        LocalTranscriber.initialize(
            context = this,
            onReady = {},
            onError = {}
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.windowId != lastWindowId) {
            lastWindowId = event.windowId
            processedNodes.clear()
        }

        val root = rootInActiveWindow ?: return
        try {
            scanForAudioMessages(root)
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() = overlayManager.removeAllOverlays()

    override fun onDestroy() {
        try { unregisterReceiver(transcriptReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(projectionReadyReceiver) } catch (_: Exception) {}
        overlayManager.removeAllOverlays()
        super.onDestroy()
    }

    // ── Tree traversal ────────────────────────────────────────────────────────

    private fun scanForAudioMessages(root: AccessibilityNodeInfo) {
        traverseTree(root) { node ->
            if (isAudioMessageNode(node)) {
                val key = nodeKey(node)
                if (key !in processedNodes) {
                    processedNodes.add(key)
                    showOverlayForNode(node, key)
                }
            }
        }
    }

    private fun traverseTree(
        node: AccessibilityNodeInfo,
        visitor: (AccessibilityNodeInfo) -> Unit
    ) {
        visitor(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseTree(child, visitor)
            child.recycle()
        }
    }

    // ── Audio node detection ──────────────────────────────────────────────────

    /**
     * Detects voice/audio message play controls across WhatsApp, Telegram,
     * Signal, Messenger, Instagram, and generic chat apps.
     *
     * Looks for clickable nodes (buttons / image views) whose content
     * description or text references audio playback.
     */
    private fun isAudioMessageNode(node: AccessibilityNodeInfo): Boolean {
        val desc  = node.contentDescription?.toString()?.lowercase() ?: ""
        val text  = node.text?.toString()?.lowercase() ?: ""
        val clazz = node.className?.toString() ?: ""

        val isControl = node.isClickable ||
                clazz.contains("Button", ignoreCase = true) ||
                clazz.contains("ImageView", ignoreCase = true)

        if (!isControl) return false

        val combined = "$desc $text"
        return audioKeywords.any { combined.contains(it) }
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
            overlayManager.updateTranscript(nodeId, "⏳ Model still loading — wait a moment and try again")
            return
        }

        if (!AudioCaptureService.isProjectionReady) {
            // Ask the user to open the app and grant audio capture permission
            overlayManager.updateTranscript(
                nodeId,
                "🔑 Open Audio Transcriber app first and tap \"Enable Audio Capture\""
            )
            val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("request_projection", true)
            }
            launch?.let { startActivity(it) }
            return
        }

        overlayManager.setRecordingState(nodeId)

        startService(
            Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START_CAPTURE
                putExtra(AudioCaptureService.EXTRA_NODE_ID, nodeId)
            }
        )
    }

    private fun onStopRequested(nodeId: String) {
        overlayManager.setTranscribingState(nodeId)
        startService(
            Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_STOP_CAPTURE
                putExtra(AudioCaptureService.EXTRA_NODE_ID, nodeId)
            }
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun nodeKey(node: AccessibilityNodeInfo): String {
        val r = Rect()
        node.getBoundsInScreen(r)
        return "${node.className}@${r.left},${r.top},${r.right},${r.bottom}"
    }

    companion object {
        private val audioKeywords = listOf(
            "voice message", "audio message", "voice note", "audio note",
            "play audio", "play voice", "play message", "audio clip",
            "voice clip", "voice recording", "sound message",
            "vocal message", "audio recording"
        )
    }
}

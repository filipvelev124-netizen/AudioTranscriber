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

    // Lazy so onInterrupt() can never crash with UninitializedPropertyAccessException
    private val overlayManager by lazy { OverlayManager(this) }

    private val processedNodes = mutableSetOf<String>()
    private var lastWindowId = -1
    private var receiversRegistered = false

    private val transcriptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                // Validate lengths — guards against crafted broadcasts on older/MIUI devices
                // where the fallback registration has no export flag
                val nodeId = intent.getStringExtra(AudioCaptureService.EXTRA_NODE_ID)
                    ?.takeIf { it.length <= 256 } ?: return
                val transcript = intent.getStringExtra(AudioCaptureService.EXTRA_TRANSCRIPT)
                    ?.take(8_192) ?: return
                overlayManager.updateTranscript(nodeId, transcript)
            } catch (e: Exception) { /* never crash the service */ }
        }
    }

    private val projectionReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { }
    }

    override fun onServiceConnected() {
        try {
            registerReceivers()
            LocalTranscriber.initialize(context = this, onReady = {}, onError = {})
        } catch (e: Exception) { /* log silently — service must not crash */ }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val resultFilter     = IntentFilter(AudioCaptureService.BROADCAST_RESULT)
        val projReadyFilter  = IntentFilter(AudioCaptureService.BROADCAST_PROJECTION_READY)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(transcriptReceiver,    resultFilter,    RECEIVER_NOT_EXPORTED)
                registerReceiver(projectionReadyReceiver, projReadyFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(transcriptReceiver,    resultFilter)
                registerReceiver(projectionReadyReceiver, projReadyFilter)
            }
            receiversRegistered = true
        } catch (e: Exception) {
            // Fallback: register without the exported flag
            try {
                registerReceiver(transcriptReceiver,    resultFilter)
                registerReceiver(projectionReadyReceiver, projReadyFilter)
                receiversRegistered = true
            } catch (e2: Exception) { /* give up silently */ }
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
                root.recycle()
            }
        } catch (e: Exception) { /* never crash the service */ }
    }

    override fun onInterrupt() {
        try { overlayManager.removeAllOverlays() } catch (e: Exception) {}
    }

    override fun onDestroy() {
        try { if (receiversRegistered) unregisterReceiver(transcriptReceiver) } catch (e: Exception) {}
        try { if (receiversRegistered) unregisterReceiver(projectionReadyReceiver) } catch (e: Exception) {}
        try { overlayManager.removeAllOverlays() } catch (e: Exception) {}
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

    private fun traverseTree(node: AccessibilityNodeInfo, visitor: (AccessibilityNodeInfo) -> Unit) {
        try { visitor(node) } catch (e: Exception) {}
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                traverseTree(child, visitor)
            } finally {
                try { child.recycle() } catch (e: Exception) {}
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
            overlayManager.updateTranscript(nodeId, "⏳ Model loading — wait a moment and try again")
            return
        }
        if (!AudioCaptureService.isProjectionReady) {
            overlayManager.updateTranscript(nodeId, "🔑 Open Audio Transcriber app and tap \"Enable Audio Capture\"")
            try {
                val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("request_projection", true)
                }
                launch?.let { startActivity(it) }
            } catch (e: Exception) {}
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
        } catch (e: Exception) {
            overlayManager.updateTranscript(nodeId, "❌ Could not start capture: ${e.message}")
        }
    }

    private fun onStopRequested(nodeId: String) {
        overlayManager.setTranscribingState(nodeId)
        try {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_STOP_CAPTURE
                putExtra(AudioCaptureService.EXTRA_NODE_ID, nodeId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (e: Exception) {}
    }

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

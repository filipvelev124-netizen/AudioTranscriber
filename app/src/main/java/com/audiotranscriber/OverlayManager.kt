package com.audiotranscriber

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import java.util.concurrent.ConcurrentHashMap

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class OverlayEntry(
        val view: View,
        val btnTranscribe: Button,
        val btnStop: Button,
        val tvText: TextView
    )

    private val overlays = ConcurrentHashMap<String, OverlayEntry>()

    /**
     * Show a new floating overlay.
     *
     * @param onTranscribeClick  called when the user taps "🎙 Transcribe"
     * @param onStopClick        called when the user taps "⏹ Stop"
     */
    fun showTranscriptOverlay(
        x: Int,
        y: Int,
        nodeId: String,
        onTranscribeClick: () -> Unit,
        onStopClick: () -> Unit
    ) {
        mainHandler.post {
            // Remove any existing overlay for this nodeId synchronously — we are already
            // on the main thread (inside mainHandler.post), so calling removeOverlay() here
            // would re-post a second lambda that runs AFTER we add the new overlay, deleting
            // the new one instead of the old one.
            overlays.remove(nodeId)?.let {
                try { windowManager.removeView(it.view) } catch (_: Throwable) {}
            }

            val view = LayoutInflater.from(context).inflate(R.layout.overlay_transcript, null)
            val tvText       = view.findViewById<TextView>(R.id.transcriptText)
            val btnTranscribe = view.findViewById<Button>(R.id.btnTranscribe)
            val btnStop      = view.findViewById<Button>(R.id.btnStop)
            val btnClose     = view.findViewById<ImageButton>(R.id.btnClose)

            btnTranscribe.setOnClickListener { onTranscribeClick() }
            btnStop.setOnClickListener { onStopClick() }
            btnClose.setOnClickListener { removeOverlay(nodeId) }

            val params = layoutParams(x, y)
            try {
                windowManager.addView(view, params)
                overlays[nodeId] = OverlayEntry(view, btnTranscribe, btnStop, tvText)
                // Auto-dismiss after 30 seconds if user ignores it
                mainHandler.postDelayed({ removeOverlay(nodeId) }, 30_000L)
            } catch (_: Throwable) {
                // SYSTEM_ALERT_WINDOW not yet granted
            }
        }
    }

    /** Switch the overlay into "Recording" state. */
    fun setRecordingState(nodeId: String) {
        mainHandler.post {
            val entry = overlays[nodeId] ?: return@post
            entry.tvText.text = "🔴 Recording… play the message now"
            entry.btnTranscribe.isVisible = false
            entry.btnStop.isVisible = true
        }
    }

    /** Switch the overlay into "Transcribing" state. */
    fun setTranscribingState(nodeId: String) {
        mainHandler.post {
            val entry = overlays[nodeId] ?: return@post
            entry.tvText.text = "⏳ Transcribing…"
            entry.btnTranscribe.isVisible = false
            entry.btnStop.isVisible = false
        }
    }

    /** Show the final transcript (or any status text) and restore idle buttons. */
    fun updateTranscript(nodeId: String, text: String) {
        mainHandler.post {
            val entry = overlays[nodeId] ?: return@post
            entry.tvText.text = text
            entry.btnTranscribe.isVisible = true
            entry.btnStop.isVisible = false
        }
    }

    fun removeOverlay(nodeId: String) {
        mainHandler.post {
            overlays.remove(nodeId)?.let {
                try { windowManager.removeView(it.view) } catch (_: Throwable) {}
            }
        }
    }

    fun removeAllOverlays() {
        overlays.keys.toList().forEach { removeOverlay(it) }
    }

    private fun layoutParams(x: Int, y: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).also { p ->
        p.gravity = Gravity.TOP or Gravity.START
        p.x = x
        p.y = y
    }
}

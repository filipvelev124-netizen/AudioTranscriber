package com.audiotranscriber

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var tvModelStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvCaptureStatus: TextView
    private lateinit var btnDownloadModel: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnAudioCapture: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView

    // Modern Activity Result API for MediaProjection permission
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startAudioCaptureService(result.resultCode, result.data!!)
        } else {
            tvCaptureStatus.text = "Audio capture: ❌ Permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvModelStatus       = findViewById(R.id.tvModelStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvOverlayStatus     = findViewById(R.id.tvOverlayStatus)
        tvCaptureStatus     = findViewById(R.id.tvCaptureStatus)
        btnDownloadModel    = findViewById(R.id.btnDownloadModel)
        btnAccessibility    = findViewById(R.id.btnAccessibility)
        btnOverlay          = findViewById(R.id.btnOverlay)
        btnAudioCapture     = findViewById(R.id.btnAudioCapture)
        progressBar         = findViewById(R.id.progressBar)
        tvProgress          = findViewById(R.id.tvProgress)

        btnDownloadModel.setOnClickListener { downloadModel() }
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnOverlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
            )
        }
        btnAudioCapture.setOnClickListener { requestAudioCapturePermission() }

        // If launched by the accessibility service requesting projection
        if (intent?.getBooleanExtra("request_projection", false) == true) {
            requestAudioCapturePermission()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Status ────────────────────────────────────────────────────────────────

    private fun refreshStatus() {
        // Step 1: model
        if (ModelDownloader.isDownloaded(this)) {
            tvModelStatus.text = "Speech model: ✅ Downloaded"
            btnDownloadModel.text = "Re-download model"
        } else {
            tvModelStatus.text = "Speech model: ❌ Not downloaded"
            btnDownloadModel.text = "Download model  (~45 MB, one-time)"
        }

        // Step 2: accessibility
        if (isAccessibilityEnabled()) {
            tvAccessibilityStatus.text = "Accessibility service: ✅ Active"
            btnAccessibility.text = "Manage accessibility settings"
        } else {
            tvAccessibilityStatus.text = "Accessibility service: ❌ Not enabled"
            btnAccessibility.text = "Enable accessibility service"
        }

        // Step 3: overlay
        if (Settings.canDrawOverlays(this)) {
            tvOverlayStatus.text = "Overlay permission: ✅ Granted"
            btnOverlay.text = "Manage overlay permission"
        } else {
            tvOverlayStatus.text = "Overlay permission: ❌ Not granted"
            btnOverlay.text = "Grant overlay permission"
        }

        // Step 4: audio capture
        if (AudioCaptureService.isProjectionReady) {
            tvCaptureStatus.text = "Audio capture: ✅ Active"
            btnAudioCapture.text = "Re-enable audio capture"
        } else {
            tvCaptureStatus.text = "Audio capture: ❌ Not active"
            btnAudioCapture.text = "Enable audio capture"
        }

        // Load model into memory if ready
        if (ModelDownloader.isDownloaded(this) && !LocalTranscriber.isReady) {
            LocalTranscriber.initialize(
                context = this,
                onReady = { tvModelStatus.text = "Speech model: ✅ Loaded" },
                onError = { e -> tvModelStatus.text = "Speech model: ⚠️ $e" }
            )
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    // ── Audio capture permission ──────────────────────────────────────────────

    private fun requestAudioCapturePermission() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startAudioCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_INIT_PROJECTION
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_PROJECTION_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        tvCaptureStatus.text = "Audio capture: ✅ Active"
        btnAudioCapture.text = "Re-enable audio capture"
    }

    // ── Model download ────────────────────────────────────────────────────────

    private fun downloadModel() {
        btnDownloadModel.isEnabled = false
        progressBar.isVisible = true
        tvProgress.isVisible = true

        scope.launch {
            ModelDownloader.download(
                context = this@MainActivity,
                onProgress = { pct ->
                    when (pct) {
                        -1 -> {
                            progressBar.isIndeterminate = true
                            tvProgress.text = "Extracting…"
                        }
                        else -> {
                            progressBar.isIndeterminate = false
                            progressBar.progress = pct
                            tvProgress.text = "Downloading…  $pct%"
                        }
                    }
                },
                onComplete = {
                    progressBar.isVisible = false
                    tvProgress.isVisible = false
                    btnDownloadModel.isEnabled = true
                    refreshStatus()
                },
                onError = { err ->
                    progressBar.isVisible = false
                    tvProgress.text = "❌ $err"
                    tvProgress.isVisible = true
                    btnDownloadModel.isEnabled = true
                }
            )
        }
    }
}

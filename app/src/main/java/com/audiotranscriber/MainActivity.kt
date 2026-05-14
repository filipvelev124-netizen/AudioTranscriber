package com.audiotranscriber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var tvModelStatus: TextView
    private lateinit var tvPermissionsStatus: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var btnDownloadModel: Button
    private lateinit var btnPermissions: Button
    private lateinit var btnService: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView

    // Request RECORD_AUDIO + POST_NOTIFICATIONS together.
    // POST_NOTIFICATIONS (Android 13+) is required for startForeground() to show
    // a visible notification. Without a visible notification, MIUI treats the service
    // as a background process and force-kills it, producing "keeps stopping" crashes.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvModelStatus       = findViewById(R.id.tvModelStatus)
        tvPermissionsStatus = findViewById(R.id.tvPermissionsStatus)
        tvServiceStatus     = findViewById(R.id.tvServiceStatus)
        btnDownloadModel    = findViewById(R.id.btnDownloadModel)
        btnPermissions      = findViewById(R.id.btnPermissions)
        btnService          = findViewById(R.id.btnService)
        progressBar         = findViewById(R.id.progressBar)
        tvProgress          = findViewById(R.id.tvProgress)

        btnDownloadModel.setOnClickListener { downloadModel() }
        btnPermissions.setOnClickListener { requestMissingPermissions() }
        btnService.setOnClickListener { toggleService() }

        requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun requestMissingPermissions() {
        val needed = mutableListOf<String>()
        if (!hasMicPermission()) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun toggleService() {
        if (AudioCaptureService.isRunning) {
            sendServiceAction(AudioCaptureService.ACTION_STOP_SERVICE)
        } else {
            sendServiceAction(AudioCaptureService.ACTION_START_CAPTURE)
        }
        // Refresh after a short delay to let the service update its flag
        btnService.postDelayed({ refreshStatus() }, 400)
    }

    private fun sendServiceAction(action: String) {
        try {
            val intent = Intent(this, AudioCaptureService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (_: Throwable) {}
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

        // Step 2: permissions
        val micOk  = hasMicPermission()
        val notifOk = hasNotificationPermission()
        when {
            micOk && notifOk -> {
                tvPermissionsStatus.text = "Permissions: ✅ All granted"
                btnPermissions.text = "Permissions granted"
                btnPermissions.isEnabled = false
            }
            micOk -> {
                tvPermissionsStatus.text = "Permissions: ⚠️ Notifications not granted"
                btnPermissions.text = "Grant notification permission"
                btnPermissions.isEnabled = true
            }
            else -> {
                tvPermissionsStatus.text = "Permissions: ❌ Microphone not granted"
                btnPermissions.text = "Grant permissions"
                btnPermissions.isEnabled = true
            }
        }

        // Step 3: service
        if (AudioCaptureService.isRunning) {
            tvServiceStatus.text = if (AudioCaptureService.isRecording)
                "Service: 🔴 Recording…"
            else
                "Service: ✅ Running (use notification to transcribe)"
            btnService.text = "Stop service"
        } else {
            tvServiceStatus.text = "Service: ❌ Not running"
            btnService.text = "Start service"
        }

        // Pre-load model into memory if downloaded but not yet loaded
        if (ModelDownloader.isDownloaded(this) && !LocalTranscriber.isReady) {
            LocalTranscriber.initialize(
                context = this,
                onReady = { tvModelStatus.text = "Speech model: ✅ Loaded" },
                onError = { e -> tvModelStatus.text = "Speech model: ⚠️ $e" }
            )
        }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true

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

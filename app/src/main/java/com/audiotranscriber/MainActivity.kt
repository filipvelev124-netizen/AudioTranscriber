package com.audiotranscriber

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
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
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnDownloadModel: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnOverlay: Button
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

        tvModelStatus         = findViewById(R.id.tvModelStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvOverlayStatus       = findViewById(R.id.tvOverlayStatus)
        btnDownloadModel      = findViewById(R.id.btnDownloadModel)
        btnAccessibility      = findViewById(R.id.btnAccessibility)
        btnOverlay            = findViewById(R.id.btnOverlay)
        progressBar           = findViewById(R.id.progressBar)
        tvProgress            = findViewById(R.id.tvProgress)

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

        // Load model into memory if downloaded but not yet loaded
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

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
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

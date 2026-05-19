package com.audiotranscriber

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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

    private lateinit var btnLanguage: Button
    private lateinit var tvModelStatus: TextView
    private lateinit var tvPermissionsStatus: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvNotifAccessStatus: TextView
    private lateinit var tvRestrictedHint: TextView
    private lateinit var btnDownloadModel: Button
    private lateinit var btnPermissions: Button
    private lateinit var btnService: Button
    private lateinit var btnNotifAccess: Button
    private lateinit var btnAppSettings: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnLanguage         = findViewById(R.id.btnLanguage)
        tvModelStatus       = findViewById(R.id.tvModelStatus)
        tvPermissionsStatus = findViewById(R.id.tvPermissionsStatus)
        tvServiceStatus     = findViewById(R.id.tvServiceStatus)
        tvNotifAccessStatus = findViewById(R.id.tvNotifAccessStatus)
        tvRestrictedHint    = findViewById(R.id.tvRestrictedHint)
        btnDownloadModel    = findViewById(R.id.btnDownloadModel)
        btnPermissions      = findViewById(R.id.btnPermissions)
        btnService          = findViewById(R.id.btnService)
        btnNotifAccess      = findViewById(R.id.btnNotifAccess)
        btnAppSettings      = findViewById(R.id.btnAppSettings)
        progressBar         = findViewById(R.id.progressBar)
        tvProgress          = findViewById(R.id.tvProgress)

        btnLanguage.setOnClickListener { showLanguagePicker() }
        btnDownloadModel.setOnClickListener { downloadModel() }
        btnPermissions.setOnClickListener { requestMissingPermissions() }
        btnService.setOnClickListener { toggleService() }
        btnNotifAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btnAppSettings.setOnClickListener {
            // Opens the app's own settings page where the user can tap ⋮ →
            // "Allow restricted settings" to unlock notification access on Android 13+
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }

        updateLanguageButton()
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

    // ── Language picker ───────────────────────────────────────────────────────

    private fun updateLanguageButton() {
        btnLanguage.text = Language.getSelected(this).displayName
    }

    private fun showLanguagePicker() {
        val languages = Language.values()
        val names = languages.map { lang ->
            val downloaded = ModelDownloader.isDownloaded(this, lang)
            if (downloaded) "✅ ${lang.displayName}" else lang.displayName
        }.toTypedArray()
        val currentIndex = languages.indexOf(Language.getSelected(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Select language")
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                val chosen = languages[which]
                val prev   = Language.getSelected(this)
                Language.setSelected(this, chosen)
                updateLanguageButton()
                if (chosen != prev) {
                    // Unload the in-memory model so the correct language is loaded next time
                    LocalTranscriber.reset()
                }
                refreshStatus()
                dialog.dismiss()
            }
            .show()
    }

    // ── Service toggle ────────────────────────────────────────────────────────

    private fun toggleService() {
        if (AudioCaptureService.isRunning) {
            sendServiceIntent(AudioCaptureService.ACTION_STOP_SERVICE)
        } else {
            // Start in idle state (no action = onCreate → idle notification, no recording)
            val intent = Intent(this, AudioCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
        btnService.postDelayed({ refreshStatus() }, 400)
    }

    private fun sendServiceIntent(action: String) {
        try {
            val intent = Intent(this, AudioCaptureService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (_: Throwable) {}
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
        val lang = Language.getSelected(this)

        // Step 1: model
        if (ModelDownloader.isDownloaded(this, lang)) {
            tvModelStatus.text = "Speech model: ✅ Downloaded (${lang.displayName})"
            btnDownloadModel.text = "Re-download model"
        } else {
            tvModelStatus.text = "Speech model: ❌ Not downloaded"
            btnDownloadModel.text = "Download ${lang.displayName} model (~45 MB)"
        }

        // Step 2: permissions
        val micOk   = hasMicPermission()
        val notifOk = hasNotificationPermission()
        when {
            micOk && notifOk -> {
                tvPermissionsStatus.text = "Permissions: ✅ All granted"
                btnPermissions.isEnabled = false
                btnPermissions.text = "Permissions granted"
            }
            micOk -> {
                tvPermissionsStatus.text = "Permissions: ⚠️ Notifications not granted"
                btnPermissions.isEnabled = true
                btnPermissions.text = "Grant notification permission"
            }
            else -> {
                tvPermissionsStatus.text = "Permissions: ❌ Microphone not granted"
                btnPermissions.isEnabled = true
                btnPermissions.text = "Grant permissions"
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

        // Step 4: notification access
        if (isNotificationAccessGranted()) {
            tvNotifAccessStatus.text = "Notification access: ✅ Granted — auto-detection active"
            btnNotifAccess.text = "Manage notification access"
            tvRestrictedHint.isVisible = false
            btnAppSettings.isVisible = false
        } else {
            tvNotifAccessStatus.text = "Notification access: ❌ Not granted"
            btnNotifAccess.text = "Enable notification access"
            // Show restricted-settings hint for Android 13+ sideloaded builds
            val showHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            tvRestrictedHint.isVisible = showHint
            btnAppSettings.isVisible = showHint
        }

        // Pre-load model into memory if downloaded for the current language
        if (ModelDownloader.isDownloaded(this, lang) && !LocalTranscriber.isReady) {
            LocalTranscriber.initialize(
                context = this,
                onReady = { tvModelStatus.text = "Speech model: ✅ Loaded (${lang.displayName})" },
                onError = { e -> tvModelStatus.text = "Speech model: ⚠️ $e" }
            )
        }
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.contains(packageName)
    }

    // ── Model download ────────────────────────────────────────────────────────

    private fun downloadModel() {
        btnDownloadModel.isEnabled = false
        progressBar.isVisible = true
        tvProgress.isVisible = true

        val language = Language.getSelected(this)

        scope.launch {
            ModelDownloader.download(
                context  = this@MainActivity,
                language = language,
                onProgress = { pct ->
                    when (pct) {
                        -1 -> { progressBar.isIndeterminate = true; tvProgress.text = "Extracting…" }
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

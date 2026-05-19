package com.audiotranscriber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
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

    private lateinit var spinnerLanguage: Spinner
    private lateinit var tvLanguageHint: TextView
    private lateinit var tvModelStatus: TextView
    private lateinit var tvPermissionsStatus: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvNotifAccessStatus: TextView
    private lateinit var btnDownloadModel: Button
    private lateinit var btnPermissions: Button
    private lateinit var btnService: Button
    private lateinit var btnNotifAccess: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView

    private var spinnerReady = false  // suppress the initial programmatic selection event

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerLanguage       = findViewById(R.id.spinnerLanguage)
        tvLanguageHint        = findViewById(R.id.tvLanguageHint)
        tvModelStatus         = findViewById(R.id.tvModelStatus)
        tvPermissionsStatus   = findViewById(R.id.tvPermissionsStatus)
        tvServiceStatus       = findViewById(R.id.tvServiceStatus)
        tvNotifAccessStatus   = findViewById(R.id.tvNotifAccessStatus)
        btnDownloadModel      = findViewById(R.id.btnDownloadModel)
        btnPermissions        = findViewById(R.id.btnPermissions)
        btnService            = findViewById(R.id.btnService)
        btnNotifAccess        = findViewById(R.id.btnNotifAccess)
        progressBar           = findViewById(R.id.progressBar)
        tvProgress            = findViewById(R.id.tvProgress)

        setupLanguageSpinner()

        btnDownloadModel.setOnClickListener { downloadModel() }
        btnPermissions.setOnClickListener { requestMissingPermissions() }
        btnService.setOnClickListener { toggleService() }
        btnNotifAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
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

    // ── Language spinner ──────────────────────────────────────────────────────

    private fun setupLanguageSpinner() {
        val languages = Language.values()
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            languages.map { it.displayName }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerLanguage.adapter = adapter

        val selectedIndex = languages.indexOf(Language.getSelected(this)).coerceAtLeast(0)
        spinnerLanguage.setSelection(selectedIndex, false)
        spinnerReady = true

        spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                if (!spinnerReady) return
                val chosen = languages[pos]
                val prev   = Language.getDownloaded(this@MainActivity)
                Language.setSelected(this@MainActivity, chosen)

                if (prev != null && prev != chosen) {
                    // Different language selected — clear the old model so the status
                    // shows "not downloaded" and the user knows they need to re-download
                    LocalTranscriber.reset()
                    try { ModelDownloader.modelDir(this@MainActivity).deleteRecursively() }
                    catch (_: Throwable) {}
                    Language.clearDownloaded(this@MainActivity)
                    tvLanguageHint.text = "Language changed — tap Download model to get the ${chosen.displayName} model."
                } else {
                    tvLanguageHint.text = ""
                }
                refreshStatus()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    // ── Service toggle ────────────────────────────────────────────────────────

    private fun toggleService() {
        if (AudioCaptureService.isRunning) {
            sendServiceIntent(AudioCaptureService.ACTION_STOP_SERVICE)
        } else {
            // Start the service in idle state (no action = onCreate → idle notification)
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
        // Step 1: model
        if (ModelDownloader.isDownloaded(this)) {
            val lang = Language.getSelected(this)
            tvModelStatus.text = "Speech model: ✅ Downloaded (${lang.displayName})"
            btnDownloadModel.text = "Re-download model"
        } else {
            val lang = Language.getSelected(this)
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
        } else {
            tvNotifAccessStatus.text = "Notification access: ❌ Not granted"
            btnNotifAccess.text = "Enable notification access"
        }

        // Pre-load model into memory if it's ready
        if (ModelDownloader.isDownloaded(this) && !LocalTranscriber.isReady) {
            LocalTranscriber.initialize(
                context = this,
                onReady = {
                    val lang = Language.getSelected(this)
                    tvModelStatus.text = "Speech model: ✅ Loaded (${lang.displayName})"
                },
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
        tvLanguageHint.text = ""

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

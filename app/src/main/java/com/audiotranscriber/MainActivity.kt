package com.audiotranscriber

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
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

    private lateinit var spinnerLanguage: Spinner
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

    private var selectedLanguage: Language = Language.ENGLISH

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

        ModelDownloader.migrateOldModel(this)

        spinnerLanguage         = findViewById(R.id.spinnerLanguage)
        tvModelStatus           = findViewById(R.id.tvModelStatus)
        tvAccessibilityStatus   = findViewById(R.id.tvAccessibilityStatus)
        tvOverlayStatus         = findViewById(R.id.tvOverlayStatus)
        tvCaptureStatus         = findViewById(R.id.tvCaptureStatus)
        btnDownloadModel        = findViewById(R.id.btnDownloadModel)
        btnAccessibility        = findViewById(R.id.btnAccessibility)
        btnOverlay              = findViewById(R.id.btnOverlay)
        btnAudioCapture         = findViewById(R.id.btnAudioCapture)
        progressBar             = findViewById(R.id.progressBar)
        tvProgress              = findViewById(R.id.tvProgress)

        setupLanguageSpinner()

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

    // ── Language spinner ──────────────────────────────────────────────────────

    private fun setupLanguageSpinner() {
        val languages = Language.entries
        selectedLanguage = loadSavedLanguage()

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languages.map { it.displayName }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spinnerLanguage.adapter = adapter
        spinnerLanguage.setSelection(languages.indexOf(selectedLanguage))

        spinnerLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val picked = languages[position]
                if (picked != selectedLanguage) {
                    selectedLanguage = picked
                    saveLanguage(picked)
                    refreshStatus()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun loadSavedLanguage(): Language {
        val code = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LANGUAGE, Language.ENGLISH.code) ?: Language.ENGLISH.code
        return Language.fromCode(code)
    }

    private fun saveLanguage(language: Language) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_LANGUAGE, language.code).apply()
    }

    // ── Status ────────────────────────────────────────────────────────────────

    private fun refreshStatus() {
        // Step 1: model (language-specific)
        if (ModelDownloader.isDownloaded(this, selectedLanguage)) {
            tvModelStatus.text = "Speech model: ✅ Downloaded (${selectedLanguage.displayName})"
            btnDownloadModel.text = "Re-download ${selectedLanguage.displayName} model"
        } else {
            tvModelStatus.text = "Speech model: ❌ Not downloaded"
            btnDownloadModel.text = "Download ${selectedLanguage.displayName} model  (~${selectedLanguage.approxSizeMb} MB, one-time)"
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

        // Load model into memory if downloaded and not already loaded for this language
        if (ModelDownloader.isDownloaded(this, selectedLanguage) &&
            !LocalTranscriber.isLoadedFor(selectedLanguage)) {
            LocalTranscriber.initialize(
                context = this,
                language = selectedLanguage,
                onReady = { tvModelStatus.text = "Speech model: ✅ Loaded (${selectedLanguage.displayName})" },
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

        val language = selectedLanguage

        scope.launch {
            ModelDownloader.download(
                context = this@MainActivity,
                language = language,
                onProgress = { pct ->
                    when (pct) {
                        -1 -> {
                            progressBar.isIndeterminate = true
                            tvProgress.text = "Extracting…"
                        }
                        else -> {
                            progressBar.isIndeterminate = false
                            progressBar.progress = pct
                            tvProgress.text = "Downloading ${language.displayName} model…  $pct%"
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

    companion object {
        private const val PREFS_NAME = "audio_transcriber_prefs"
        private const val PREF_LANGUAGE = "selected_language"
    }
}

package com.audiotranscriber

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomnavigation.BottomNavigationView

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchAutoCopy: SwitchCompat
    private lateinit var switchService: SwitchCompat
    private lateinit var tvModelStatus: TextView
    private lateinit var tvSensitivityValue: TextView
    private lateinit var tvNotifStatus: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvAppFilterSummary: TextView
    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_settings)

        switchAutoCopy     = findViewById(R.id.switchAutoCopy)
        switchService      = findViewById(R.id.switchService)
        tvModelStatus      = findViewById(R.id.tvModelStatus)
        tvSensitivityValue = findViewById(R.id.tvSensitivityValue)
        tvNotifStatus      = findViewById(R.id.tvNotifStatus)
        tvServiceStatus    = findViewById(R.id.tvServiceStatus)
        tvAppFilterSummary = findViewById(R.id.tvAppFilterSummary)
        bottomNav          = findViewById(R.id.bottomNav)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        setupSwitches()
        setupRows()
        setupBottomNav()
        setVersionText()
    }

    override fun onResume() {
        super.onResume()
        refreshDynamicValues()
    }

    // ── Bottom nav ──────────────────────────────────────────────────────────

    private fun setupBottomNav() {
        bottomNav.selectedItemId = R.id.nav_settings
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_transcribe -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish(); false
                }
                R.id.nav_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    finish(); false
                }
                else -> true
            }
        }
    }

    // ── Switches ────────────────────────────────────────────────────────────

    private fun setupSwitches() {
        switchAutoCopy.isChecked = AppPrefs.isAutoCopy(this)
        switchAutoCopy.setOnCheckedChangeListener { _, v -> AppPrefs.setAutoCopy(this, v) }

        switchService.isChecked = AudioCaptureService.isRunning
        switchService.setOnCheckedChangeListener { _, v ->
            if (v) {
                val intent = Intent(this, AudioCaptureService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            } else {
                startService(Intent(this, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_STOP_SERVICE
                })
            }
        }
    }

    // ── Navigation rows ──────────────────────────────────────────────────────

    private fun setupRows() {
        findViewById<LinearLayout>(R.id.rowWhisperModel).setOnClickListener { showModelOptions() }
        findViewById<LinearLayout>(R.id.rowNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<LinearLayout>(R.id.rowSensitivity).setOnClickListener { showSensitivityDialog() }
        findViewById<LinearLayout>(R.id.rowAppFilter).setOnClickListener { showAppFilterDialog() }
    }

    // ── Dynamic values ───────────────────────────────────────────────────────

    private fun refreshDynamicValues() {
        updateModelStatus()
        updateSensitivityValue()
        updateNotifStatus()
        updateServiceStatus()
        updateAppFilterSummary()
    }

    private fun updateModelStatus() {
        tvModelStatus.text = when {
            WhisperEngine.isModelDownloaded(this) -> "Ready — all languages offline"
            else -> "Not downloaded — tap to download (~75 MB)"
        }
    }

    private fun updateSensitivityValue() {
        tvSensitivityValue.text = "${AppPrefs.getSensitivity(this) + 1} / 10"
    }

    private fun updateNotifStatus() {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        tvNotifStatus.text = if (flat.contains(packageName)) "Granted — auto-detect active"
        else "Not granted — tap to enable"
    }

    private fun updateServiceStatus() {
        val running = AudioCaptureService.isRunning
        switchService.setOnCheckedChangeListener(null)
        switchService.isChecked = running
        switchService.setOnCheckedChangeListener { _, v ->
            if (v) {
                val intent = Intent(this, AudioCaptureService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            } else {
                startService(Intent(this, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_STOP_SERVICE
                })
            }
        }
        tvServiceStatus.text = if (running) "Running in background" else "Not running"
    }

    private fun updateAppFilterSummary() {
        val disabled = AppPrefs.getDisabledPackages(this)
        val activeCount = MESSAGING_APPS.count { it.packageName !in disabled }
        tvAppFilterSummary.text = "$activeCount of ${MESSAGING_APPS.size} apps active"
    }

    // ── Whisper model options ────────────────────────────────────────────────

    private fun showModelOptions() {
        if (!WhisperEngine.isModelDownloaded(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Whisper Model")
            .setMessage("Model is ready. All languages including Bulgarian work offline.\n\nRe-download if you suspect the model file is corrupted.")
            .setNeutralButton("Re-download") { _, _ ->
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Sensitivity dialog ───────────────────────────────────────────────────

    private fun showSensitivityDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_sensitivity, null)
        val seekBar = view.findViewById<SeekBar>(R.id.seekSensitivity)
        val tvLabel = view.findViewById<TextView>(R.id.tvSensitivityLabel)

        fun updateLabel(p: Int) { tvLabel.text = "${p + 1} / 10" }

        seekBar.max = 9
        seekBar.progress = AppPrefs.getSensitivity(this)
        updateLabel(seekBar.progress)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = updateLabel(p)
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle("Microphone Sensitivity")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                AppPrefs.setSensitivity(this, seekBar.progress)
                updateSensitivityValue()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── App filter dialog ────────────────────────────────────────────────────

    private fun showAppFilterDialog() {
        val disabled = AppPrefs.getDisabledPackages(this).toMutableSet()
        val names    = MESSAGING_APPS.map { it.displayName }.toTypedArray()
        val checked  = MESSAGING_APPS.map { it.packageName !in disabled }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Auto-detect voice messages in:")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val pkg = MESSAGING_APPS[which].packageName
                if (isChecked) disabled.remove(pkg) else disabled.add(pkg)
            }
            .setPositiveButton("Save") { _, _ ->
                AppPrefs.setDisabledPackages(this, disabled)
                updateAppFilterSummary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Version ──────────────────────────────────────────────────────────────

    private fun setVersionText() {
        try {
            val ver = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.tvVersion).text = "ScribePro v$ver"
        } catch (_: Exception) {}
    }
}

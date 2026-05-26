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
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchUseCloud: SwitchCompat
    private lateinit var switchAutoCopy: SwitchCompat
    private lateinit var switchService: SwitchCompat
    private lateinit var rowHfToken: LinearLayout
    private lateinit var dividerHfToken: android.view.View
    private lateinit var tvTokenPreview: TextView
    private lateinit var tvModelValue: TextView
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

        switchUseCloud      = findViewById(R.id.switchUseCloud)
        switchAutoCopy      = findViewById(R.id.switchAutoCopy)
        switchService       = findViewById(R.id.switchService)
        rowHfToken          = findViewById(R.id.rowHfToken)
        dividerHfToken      = findViewById(R.id.dividerHfToken)
        tvTokenPreview      = findViewById(R.id.tvTokenPreview)
        tvModelValue        = findViewById(R.id.tvModelValue)
        tvSensitivityValue  = findViewById(R.id.tvSensitivityValue)
        tvNotifStatus       = findViewById(R.id.tvNotifStatus)
        tvServiceStatus     = findViewById(R.id.tvServiceStatus)
        tvAppFilterSummary  = findViewById(R.id.tvAppFilterSummary)
        bottomNav           = findViewById(R.id.bottomNav)

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
                    finish()
                    false
                }
                R.id.nav_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    finish()
                    false
                }
                else -> true
            }
        }
    }

    // ── Switches ────────────────────────────────────────────────────────────

    private fun setupSwitches() {
        switchAutoCopy.isChecked = AppPrefs.isAutoCopy(this)
        switchAutoCopy.setOnCheckedChangeListener { _, v -> AppPrefs.setAutoCopy(this, v) }

        switchUseCloud.isChecked = AppPrefs.isUseCloud(this)
        switchUseCloud.setOnCheckedChangeListener { _, v ->
            AppPrefs.setUseCloud(this, v)
            updateCloudRows(v)
            updateModelValue()
        }

        switchService.isChecked = AudioCaptureService.isRunning
        switchService.setOnCheckedChangeListener { _, v ->
            if (v) {
                val intent = Intent(this, AudioCaptureService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            } else {
                val intent = Intent(this, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_STOP_SERVICE
                }
                startService(intent)
            }
        }
    }

    private fun updateCloudRows(cloudOn: Boolean) {
        rowHfToken.isVisible     = cloudOn
        dividerHfToken.isVisible = cloudOn
    }

    // ── Navigation rows ──────────────────────────────────────────────────────

    private fun setupRows() {
        rowHfToken.setOnClickListener { showTokenDialog() }
        findViewById<LinearLayout>(R.id.rowNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<LinearLayout>(R.id.rowSensitivity).setOnClickListener {
            showSensitivityDialog()
        }
        findViewById<LinearLayout>(R.id.rowAppFilter).setOnClickListener {
            showAppFilterDialog()
        }
    }

    // ── Dynamic values ───────────────────────────────────────────────────────

    private fun refreshDynamicValues() {
        val useCloud = AppPrefs.isUseCloud(this)
        updateCloudRows(useCloud)
        updateTokenPreview()
        updateModelValue()
        updateSensitivityValue()
        updateNotifStatus()
        updateServiceStatus()
        updateAppFilterSummary()
    }

    private fun updateTokenPreview() {
        val token = AppPrefs.getHfToken(this)
        tvTokenPreview.text = when {
            token.isBlank() -> "Not set — tap to add"
            token.length > 8 -> "●●●●●●●● …${token.takeLast(4)}"
            else -> "Set"
        }
    }

    private fun updateModelValue() {
        val useCloud = AppPrefs.isUseCloud(this)
        tvModelValue.text = when {
            useCloud -> "Whisper API"
            WhisperEngine.isModelDownloaded(this) -> "Whisper offline"
            ModelDownloader.isDownloaded(this, Language.getSelected(this)) -> "Vosk offline"
            else -> "Not downloaded"
        }
    }

    private fun updateSensitivityValue() {
        val s = AppPrefs.getSensitivity(this)
        tvSensitivityValue.text = "${s + 1} / 10"
    }

    private fun updateNotifStatus() {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: ""
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

    // ── HF Token dialog ──────────────────────────────────────────────────────

    private fun showTokenDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_token_input, null)
        val etToken = view.findViewById<TextInputEditText>(R.id.etTokenInput)
        val existing = AppPrefs.getHfToken(this)
        if (existing.isNotBlank()) etToken.setText(existing)

        AlertDialog.Builder(this)
            .setTitle("Hugging Face Token")
            .setMessage("Get a free token at huggingface.co → Settings → Access Tokens → New token (Read)")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val token = etToken.text?.toString()?.trim().orEmpty()
                AppPrefs.setHfToken(this, token)
                updateTokenPreview()
            }
            .setNegativeButton("Cancel", null)
            .also { if (existing.isNotBlank()) it.setNeutralButton("Clear") { _, _ ->
                AppPrefs.setHfToken(this, "")
                updateTokenPreview()
            }}
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

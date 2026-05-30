package com.audiotranscriber

import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchAutoCopy:      SwitchCompat
    private lateinit var switchService:       SwitchCompat
    private lateinit var tvModelStatus:       TextView
    private lateinit var tvQualityValue:      TextView
    private lateinit var tvSensitivityValue:  TextView
    private lateinit var tvNotifStatus:       TextView
    private lateinit var tvServiceStatus:     TextView
    private lateinit var tvAppFilterSummary:  TextView
    private lateinit var bottomNav:           BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_settings)

        switchAutoCopy     = findViewById(R.id.switchAutoCopy)
        switchService      = findViewById(R.id.switchService)
        tvModelStatus      = findViewById(R.id.tvModelStatus)
        tvQualityValue     = findViewById(R.id.tvQualityValue)
        tvSensitivityValue = findViewById(R.id.tvSensitivityValue)
        tvNotifStatus      = findViewById(R.id.tvNotifStatus)
        tvServiceStatus    = findViewById(R.id.tvServiceStatus)
        tvAppFilterSummary = findViewById(R.id.tvAppFilterSummary)
        bottomNav          = findViewById(R.id.bottomNav)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Account row: show a brief info toast
        findViewById<LinearLayout>(R.id.rowAccount).setOnClickListener {
            Toast.makeText(this, "ScribePro — offline AI transcription", Toast.LENGTH_SHORT).show()
        }

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

    private fun tabAnim(enterAnim: Int, exitAnim: Int) =
        ActivityOptions.makeCustomAnimation(this, enterAnim, exitAnim).toBundle()

    private fun setupBottomNav() {
        bottomNav.selectedItemId = R.id.nav_settings
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_transcribe -> {
                    startActivity(Intent(this, MainActivity::class.java),
                        tabAnim(R.anim.slide_in_left, R.anim.slide_out_right))
                    finish(); false
                }
                R.id.nav_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java),
                        tabAnim(R.anim.slide_in_left, R.anim.slide_out_right))
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
        findViewById<LinearLayout>(R.id.rowRecordingQuality).setOnClickListener { showQualityDialog() }
        findViewById<LinearLayout>(R.id.rowWhisperModel).setOnClickListener { showModelOptions() }
        findViewById<LinearLayout>(R.id.rowNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<LinearLayout>(R.id.rowSensitivity).setOnClickListener { showSensitivityDialog() }
        findViewById<LinearLayout>(R.id.rowAppFilter).setOnClickListener { showAppFilterDialog() }
        findViewById<LinearLayout>(R.id.rowFaq).setOnClickListener { showFaqDialog() }
        findViewById<LinearLayout>(R.id.rowContact).setOnClickListener { openContactSupport() }
    }

    // ── Dynamic values ───────────────────────────────────────────────────────

    private fun refreshDynamicValues() {
        updateModelStatus()
        updateQualityValue()
        updateSensitivityValue()
        updateNotifStatus()
        updateServiceStatus()
        updateAppFilterSummary()
    }

    private fun updateModelStatus() {
        val lang = Language.getSelected(this)
        tvModelStatus.text = when {
            lang.isOnline                            -> "Online — ${lang.displayName} uses cloud recognition"
            ModelDownloader.isDownloaded(this, lang) -> "Ready — ${lang.displayName} model loaded"
            else                                     -> "Downloading in background — ready soon"
        }
    }

    private fun updateQualityValue() {
        tvQualityValue.text = AppPrefs.getRecordingQuality(this)
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
        val disabled    = AppPrefs.getDisabledPackages(this)
        val activeCount = MESSAGING_APPS.count { it.packageName !in disabled }
        tvAppFilterSummary.text = "$activeCount of ${MESSAGING_APPS.size} apps active"
    }

    // ── Recording quality dialog ─────────────────────────────────────────────

    private fun showQualityDialog() {
        val options = arrayOf(
            "Fast — prioritises speed",
            "Balanced — good default",
            "Best — highest accuracy"
        )
        val labels  = arrayOf("Fast", "Balanced", "Best")
        val current = labels.indexOf(AppPrefs.getRecordingQuality(this)).coerceAtLeast(1)

        AlertDialog.Builder(this)
            .setTitle("Audio Quality")
            .setSingleChoiceItems(options, current) { dialog, which ->
                AppPrefs.setRecordingQuality(this, labels[which])
                updateQualityValue()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Model info ───────────────────────────────────────────────────────────

    private fun showModelOptions() {
        val lang = Language.getSelected(this)
        val message = when {
            lang.isOnline -> "${lang.displayName} uses cloud speech recognition. No model download needed — requires an internet connection."
            ModelDownloader.isDownloaded(this, lang) -> "${lang.displayName} model is ready for offline use.\n\nTo free up space, you can delete it — it will re-download automatically next time you transcribe."
            else -> "${lang.displayName} model is downloading in the background (~40 MB). All language models download automatically after install."
        }
        AlertDialog.Builder(this)
            .setTitle("Speech Model")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Sensitivity dialog ───────────────────────────────────────────────────

    private fun showSensitivityDialog() {
        val view    = LayoutInflater.from(this).inflate(R.layout.dialog_sensitivity, null)
        val seekBar = view.findViewById<SeekBar>(R.id.seekSensitivity)
        val tvLabel = view.findViewById<TextView>(R.id.tvSensitivityLabel)

        fun updateLabel(p: Int) { tvLabel.text = "${p + 1} / 10" }

        seekBar.max      = 9
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

    // ── FAQ dialog ───────────────────────────────────────────────────────────

    private fun showFaqDialog() {
        AlertDialog.Builder(this)
            .setTitle("FAQ")
            .setMessage(
                "Q: Does the app need internet?\n" +
                "A: Language models (~40 MB each) download automatically in the background after install. After that, everything is fully offline.\n\n" +
                "Q: Which languages are supported?\n" +
                "A: 13 languages: Bulgarian, English, Spanish, French, German, Russian, Portuguese, Italian, Chinese, Hindi, Dutch, Turkish, Korean.\n\n" +
                "Q: How do I transcribe a voice message?\n" +
                "A: Tap the mic button, then play the voice message. Transcription appears in the notification and is copied to clipboard.\n\n" +
                "Q: Where are my transcripts stored?\n" +
                "A: Locally on your device — no data is sent anywhere."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Contact support ──────────────────────────────────────────────────────

    private fun openContactSupport() {
        try {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/filipvelev124-netizen/AudioTranscriber/issues"))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Open GitHub Issues to contact support", Toast.LENGTH_LONG).show()
        }
    }

    // ── Version ──────────────────────────────────────────────────────────────

    private fun setVersionText() {
        try {
            val ver = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.tvVersion).text = "ScribePro v$ver"
        } catch (_: Exception) {}
    }
}

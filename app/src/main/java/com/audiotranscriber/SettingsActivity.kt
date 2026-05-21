package com.audiotranscriber

import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Settings"

        // ── Auto-copy ──────────────────────────────────────────────────────────
        val switchAutoCopy = findViewById<SwitchCompat>(R.id.switchAutoCopy)
        switchAutoCopy.isChecked = AppPrefs.isAutoCopy(this)
        switchAutoCopy.setOnCheckedChangeListener { _, v -> AppPrefs.setAutoCopy(this, v) }

        // ── Use cloud Whisper ──────────────────────────────────────────────────
        val switchUseCloud = findViewById<SwitchCompat>(R.id.switchUseCloud)
        switchUseCloud.isChecked = AppPrefs.isUseCloud(this)
        switchUseCloud.setOnCheckedChangeListener { _, v -> AppPrefs.setUseCloud(this, v) }

        // ── Sensitivity SeekBar ────────────────────────────────────────────────
        val seekSensitivity   = findViewById<SeekBar>(R.id.seekSensitivity)
        val tvSensitivityLabel = findViewById<TextView>(R.id.tvSensitivityLabel)

        fun updateSensitivityLabel(progress: Int) {
            tvSensitivityLabel.text = "${progress + 1}/10"
        }

        seekSensitivity.progress = AppPrefs.getSensitivity(this)
        updateSensitivityLabel(seekSensitivity.progress)

        seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSensitivityLabel(progress)
                AppPrefs.setSensitivity(this@SettingsActivity, progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ── Per-app filter ─────────────────────────────────────────────────────
        val layoutApps = findViewById<LinearLayout>(R.id.layoutApps)
        val disabled   = AppPrefs.getDisabledPackages(this).toMutableSet()

        MESSAGING_APPS.forEach { app ->
            val cb = CheckBox(this).apply {
                text      = app.displayName
                isChecked = app.packageName !in disabled
                setTextColor(resources.getColor(R.color.textPrimary, theme))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) disabled.remove(app.packageName)
                    else         disabled.add(app.packageName)
                    AppPrefs.setDisabledPackages(this@SettingsActivity, disabled)
                }
            }
            layoutApps.addView(cb)
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

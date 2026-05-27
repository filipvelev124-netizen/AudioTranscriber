package com.audiotranscriber

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var fabMic: FloatingActionButton
    private lateinit var vPulseRing: View
    private lateinit var vPulseRing2: View
    private lateinit var tvWaveformStatus: TextView
    private lateinit var llWaveformBars: LinearLayout
    private lateinit var cardSetupStatus: MaterialCardView
    private lateinit var tvSetupTitle: TextView
    private lateinit var tvSetupMessage: TextView
    private lateinit var btnSetupAction: Button
    private lateinit var chipLanguage: Chip
    private lateinit var chipMode: Chip
    private lateinit var rvRecentTranscripts: RecyclerView
    private lateinit var tvNoRecents: TextView
    private lateinit var btnViewAll: Button
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var tvModeChip: TextView
    private lateinit var tvRecordingTitle: TextView
    private lateinit var tvRecordingSubtitle: TextView

    private var pulseAnimator: AnimatorSet? = null
    private val waveformAnimators = mutableListOf<ValueAnimator>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fabMic           = findViewById(R.id.fabMic)
        vPulseRing       = findViewById(R.id.vPulseRing)
        vPulseRing2      = findViewById(R.id.vPulseRing2)
        tvWaveformStatus = findViewById(R.id.tvWaveformStatus)
        llWaveformBars   = findViewById(R.id.llWaveformBars)
        cardSetupStatus  = findViewById(R.id.cardSetupStatus)
        tvSetupTitle     = findViewById(R.id.tvSetupTitle)
        tvSetupMessage   = findViewById(R.id.tvSetupMessage)
        btnSetupAction   = findViewById(R.id.btnSetupAction)
        chipLanguage     = findViewById(R.id.chipLanguage)
        chipMode         = findViewById(R.id.chipMode)
        rvRecentTranscripts  = findViewById(R.id.rvRecentTranscripts)
        tvNoRecents          = findViewById(R.id.tvNoRecents)
        btnViewAll           = findViewById(R.id.btnViewAll)
        bottomNav            = findViewById(R.id.bottomNav)
        tvModeChip           = findViewById(R.id.tvModeChip)
        tvRecordingTitle     = findViewById(R.id.tvRecordingTitle)
        tvRecordingSubtitle  = findViewById(R.id.tvRecordingSubtitle)

        setupWaveformBars()
        setupBottomNav()
        fabMic.setOnClickListener { toggleCapture() }
        chipLanguage.setOnClickListener { showLanguagePicker() }
        chipMode.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnViewAll.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }

        requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateChips()
        refreshStatus()
        loadRecentTranscripts()
        syncRecordingUi()
    }

    override fun onDestroy() {
        stopPulse()
        stopWaveformAnimation()
        scope.cancel()
        super.onDestroy()
    }

    // ── Bottom nav ──────────────────────────────────────────────────────────

    private fun setupBottomNav() {
        bottomNav.selectedItemId = R.id.nav_transcribe
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    false
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    false
                }
                else -> true
            }
        }
    }

    // ── Waveform bars ────────────────────────────────────────────────────────

    private val idleBarHeightsDp = intArrayOf(18, 32, 48, 56, 42, 52, 38, 28, 46, 22)

    private fun setupWaveformBars() {
        val density = resources.displayMetrics.density
        idleBarHeightsDp.forEach { h ->
            val bar = View(this).apply {
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_waveform_bar)
                layoutParams = LinearLayout.LayoutParams(
                    (3 * density).toInt(),
                    (h * density).toInt()
                ).apply {
                    marginStart = (3 * density).toInt()
                    marginEnd = (3 * density).toInt()
                }
            }
            llWaveformBars.addView(bar)
        }
    }

    private fun startWaveformAnimation() {
        stopWaveformAnimation()
        val density = resources.displayMetrics.density
        for (i in 0 until llWaveformBars.childCount) {
            val bar = llWaveformBars.getChildAt(i)
            val maxH = ((16 + (i % 5) * 10 + 28) * density).toInt()
            val minH = (6 * density).toInt()
            val anim = ValueAnimator.ofInt(bar.layoutParams.height, maxH, minH).apply {
                duration = 500L + i * 70L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                startDelay = i * 50L
                addUpdateListener { animator ->
                    val lp = bar.layoutParams
                    lp.height = animator.animatedValue as Int
                    bar.layoutParams = lp
                }
            }
            anim.start()
            waveformAnimators.add(anim)
        }
    }

    private fun stopWaveformAnimation() {
        waveformAnimators.forEach { it.cancel() }
        waveformAnimators.clear()
        val density = resources.displayMetrics.density
        for (i in 0 until llWaveformBars.childCount) {
            val bar = llWaveformBars.getChildAt(i)
            val lp = bar.layoutParams
            lp.height = (idleBarHeightsDp.getOrElse(i) { 30 } * density).toInt()
            bar.layoutParams = lp
        }
    }

    // ── Pulse animation ──────────────────────────────────────────────────────

    private fun startPulse() {
        pulseAnimator?.cancel()
        val ring1 = ObjectAnimator.ofPropertyValuesHolder(
            vPulseRing,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.45f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.45f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 0f)
        ).apply {
            duration = 1100
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }
        val ring2 = ObjectAnimator.ofPropertyValuesHolder(
            vPulseRing2,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.3f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.3f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.4f, 0f)
        ).apply {
            duration = 1100
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            startDelay = 350
        }
        pulseAnimator = AnimatorSet().also { set ->
            set.playTogether(ring1, ring2)
            set.start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        vPulseRing.alpha = 0f
        vPulseRing2.alpha = 0f
    }

    // ── Recording state ──────────────────────────────────────────────────────

    private fun toggleCapture() {
        if (AudioCaptureService.isRecording) {
            sendServiceIntent(AudioCaptureService.ACTION_STOP_CAPTURE)
            setRecordingUi(false)
        } else {
            ensureServiceRunning()
            sendServiceIntent(AudioCaptureService.ACTION_START_CAPTURE)
            setRecordingUi(true)
        }
    }

    private fun setRecordingUi(recording: Boolean) {
        if (recording) {
            fabMic.setImageResource(R.drawable.ic_stop_24)
            tvWaveformStatus.text     = "Listening…"
            tvRecordingTitle.text     = "Recording…"
            tvRecordingSubtitle.text  = "Tap to stop transcription"
            startPulse()
            startWaveformAnimation()
        } else {
            fabMic.setImageResource(R.drawable.ic_mic)
            tvWaveformStatus.text     = "Ready to transcribe"
            tvRecordingTitle.text     = "Start Recording"
            tvRecordingSubtitle.text  = "Tap to capture and transcribe audio"
            stopPulse()
            stopWaveformAnimation()
        }
    }

    private fun syncRecordingUi() {
        setRecordingUi(AudioCaptureService.isRecording)
    }

    private fun ensureServiceRunning() {
        if (!AudioCaptureService.isRunning) {
            val intent = Intent(this, AudioCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
    }

    private fun sendServiceIntent(action: String) {
        try {
            val intent = Intent(this, AudioCaptureService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (_: Throwable) {}
    }

    // ── Chips ────────────────────────────────────────────────────────────────

    private fun updateChips() {
        val lang = Language.getSelected(this)
        chipLanguage.text = lang.displayName
        val engineLabel = when {
            lang.isOnline                            -> "Online"
            ModelDownloader.isDownloaded(this, lang) -> "Vosk"
            else                                     -> "Auto-download"
        }
        chipMode.text = engineLabel
        tvModeChip.text = engineLabel
    }

    // ── Language picker ───────────────────────────────────────────────────────

    private fun showLanguagePicker() {
        val languages = Language.values()
        val names = languages.map { lang ->
            if (lang.isOnline || ModelDownloader.isDownloaded(this, lang)) "✅ ${lang.displayName}"
            else lang.displayName
        }.toTypedArray()
        val currentIndex = languages.indexOf(Language.getSelected(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Select language")
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                val chosen = languages[which]
                val prev = Language.getSelected(this)
                Language.setSelected(this, chosen)
                if (chosen != prev) LocalTranscriber.reset()
                updateChips()
                refreshStatus()
                dialog.dismiss()
            }
            .show()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestMissingPermissions() {
        val needed = mutableListOf<String>()
        if (!hasMicPermission()) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true

    // ── Status / setup card ───────────────────────────────────────────────────

    private data class SetupIssue(
        val title: String,
        val message: String,
        val actionLabel: String,
        val actionType: String
    )

    private fun getSetupIssue(): SetupIssue? {
        if (!hasMicPermission()) {
            return SetupIssue(
                "Microphone permission needed",
                "Grant microphone permission so the app can capture audio.",
                "Grant", "permission"
            )
        }
        return null
    }

    private fun refreshStatus() {
        val issue = getSetupIssue()
        if (issue == null) {
            cardSetupStatus.isVisible = false
            ensureServiceRunning()
        } else {
            cardSetupStatus.isVisible = true
            tvSetupTitle.text = issue.title
            tvSetupMessage.text = issue.message
            btnSetupAction.text = issue.actionLabel
            btnSetupAction.isEnabled = true
            btnSetupAction.setOnClickListener { handleSetupAction(issue.actionType) }
        }

        val lang = Language.getSelected(this)
        if (!lang.isOnline && ModelDownloader.isDownloaded(this, lang) && !LocalTranscriber.isReady) {
            LocalTranscriber.initialize(this, onReady = {}, onError = {})
        }
    }

    private fun handleSetupAction(actionType: String) {
        when (actionType) {
            "settings"   -> startActivity(Intent(this, SettingsActivity::class.java))
            "permission" -> requestMissingPermissions()
        }
    }

    // ── Recent transcripts ────────────────────────────────────────────────────

    private fun loadRecentTranscripts() {
        scope.launch {
            val recent = withContext(Dispatchers.IO) {
                AppDatabase.get(this@MainActivity).transcriptDao().getAll().take(5)
            }
            if (recent.isEmpty()) {
                tvNoRecents.isVisible = true
                rvRecentTranscripts.isVisible = false
            } else {
                tvNoRecents.isVisible = false
                rvRecentTranscripts.isVisible = true
                rvRecentTranscripts.layoutManager =
                    LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
                rvRecentTranscripts.adapter = RecentAdapter(recent)
            }
        }
    }

    inner class RecentAdapter(private val items: List<Transcript>) :
        RecyclerView.Adapter<RecentAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvLanguage:     TextView = view.findViewById(R.id.tvLanguage)
            val tvTimestamp:    TextView = view.findViewById(R.id.tvTimestamp)
            val tvText:         TextView = view.findViewById(R.id.tvText)
            val tvExcerpt:      TextView = view.findViewById(R.id.tvExcerpt)
            val tvDuration:     TextView = view.findViewById(R.id.tvDurationRecent)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_recent_transcript, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvLanguage.text  = item.languageName
            // Title = first 60 chars of text; Excerpt = full text for context
            holder.tvText.text      = item.text.take(60).trimEnd()
            holder.tvExcerpt.text   = item.text
            holder.tvTimestamp.text = relativeTime(item.timestamp)
            if (item.durationMs > 0) {
                val secs = item.durationMs / 1000
                holder.tvDuration.text    = "%d:%02d".format(secs / 60, secs % 60)
                holder.tvDuration.visibility = View.VISIBLE
            } else {
                holder.tvDuration.visibility = View.GONE
            }
            holder.itemView.setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("transcript", item.text))
                Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }

        override fun getItemCount() = items.size
    }

    private fun relativeTime(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000L       -> "Just now"
            diff < 3_600_000L    -> "${diff / 60_000}m ago"
            diff < 86_400_000L   -> "${diff / 3_600_000}h ago"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))
        }
    }
}

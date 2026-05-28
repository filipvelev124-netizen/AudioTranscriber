package com.audiotranscriber

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var rvHistory: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var etSearch: EditText
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var btnSelect: Button
    private lateinit var btnDeleteSelected: Button
    private lateinit var btnClearAll: Button
    private lateinit var btnLoadMore: Button

    private var allItems = listOf<Transcript>()
    private lateinit var adapter: TranscriptAdapter

    private var isSelectMode = false
    private val selectedIds = mutableSetOf<Int>()
    private var itemLimit = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_history)

        rvHistory        = findViewById(R.id.rvHistory)
        tvEmpty          = findViewById(R.id.tvEmpty)
        etSearch         = findViewById(R.id.etSearch)
        bottomNav        = findViewById(R.id.bottomNav)
        btnSelect        = findViewById(R.id.btnSelect)
        btnDeleteSelected= findViewById(R.id.btnDeleteSelected)
        btnClearAll      = findViewById(R.id.btnClearAll)
        btnLoadMore      = findViewById(R.id.btnLoadMore)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        adapter = TranscriptAdapter(
            onMenu   = { t -> if (!isSelectMode) showItemMenu(t) },
            onToggle = { t -> toggleSelection(t) }
        )
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = adapter

        btnSelect.setOnClickListener { toggleSelectMode() }
        btnDeleteSelected.setOnClickListener { deleteSelected() }
        btnClearAll.setOnClickListener { confirmClearAll() }
        btnLoadMore.setOnClickListener {
            itemLimit += 20
            filterItems(etSearch.text.toString().trim())
        }

        setupBottomNav()
        setupSearch()
        loadHistory()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    // ── Bottom nav ──────────────────────────────────────────────────────────

    private fun tabAnim(enterAnim: Int, exitAnim: Int) =
        ActivityOptions.makeCustomAnimation(this, enterAnim, exitAnim).toBundle()

    private fun setupBottomNav() {
        bottomNav.selectedItemId = R.id.nav_history
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_transcribe -> {
                    startActivity(Intent(this, MainActivity::class.java),
                        tabAnim(R.anim.slide_in_left, R.anim.slide_out_right))
                    finish(); false
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java),
                        tabAnim(R.anim.slide_in_right, R.anim.slide_out_left))
                    false
                }
                else -> true
            }
        }
    }

    // ── Select mode ─────────────────────────────────────────────────────────

    private fun toggleSelectMode() {
        isSelectMode = !isSelectMode
        selectedIds.clear()
        if (isSelectMode) {
            btnSelect.text = "Cancel"
            btnClearAll.isVisible = false
            btnDeleteSelected.isVisible = true
            btnDeleteSelected.text = "Delete (0)"
        } else {
            btnSelect.text = "Select"
            btnClearAll.isVisible = true
            btnDeleteSelected.isVisible = false
        }
        adapter.setSelectMode(isSelectMode)
    }

    private fun toggleSelection(transcript: Transcript) {
        if (selectedIds.contains(transcript.id)) selectedIds.remove(transcript.id)
        else selectedIds.add(transcript.id)
        btnDeleteSelected.text = "Delete (${selectedIds.size})"
        adapter.setSelectedIds(selectedIds)
    }

    private fun deleteSelected() {
        if (selectedIds.isEmpty()) { toggleSelectMode(); return }
        AlertDialog.Builder(this)
            .setMessage("Delete ${selectedIds.size} transcript(s)?")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    val toDelete = allItems.filter { it.id in selectedIds }
                    withContext(Dispatchers.IO) {
                        val dao = AppDatabase.get(this@HistoryActivity).transcriptDao()
                        toDelete.forEach { dao.delete(it) }
                    }
                    toggleSelectMode()
                    loadHistory()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Search ──────────────────────────────────────────────────────────────

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterItems(s?.toString()?.trim().orEmpty())
            }
        })
    }

    private fun filterItems(query: String) {
        val filtered = if (query.isEmpty()) allItems
        else {
            val q = query.lowercase()
            allItems.filter { it.text.lowercase().contains(q) || it.languageName.lowercase().contains(q) }
        }
        val displayItems = if (query.isEmpty()) filtered.take(itemLimit) else filtered
        adapter.submitList(displayItems)
        showEmptyState(displayItems.isEmpty())
        btnLoadMore.isVisible = query.isEmpty() && filtered.size > itemLimit
    }

    // ── Data ────────────────────────────────────────────────────────────────

    private fun loadHistory() {
        scope.launch {
            allItems = withContext(Dispatchers.IO) {
                AppDatabase.get(this@HistoryActivity).transcriptDao().getAll()
            }
            filterItems(etSearch.text.toString().trim())
        }
    }

    private fun showEmptyState(empty: Boolean) {
        tvEmpty.isVisible   = empty
        rvHistory.isVisible = !empty
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setMessage("Delete all transcripts?")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.get(this@HistoryActivity).transcriptDao().deleteAll()
                    }
                    allItems = emptyList()
                    adapter.submitList(emptyList())
                    showEmptyState(true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showItemMenu(transcript: Transcript) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Copy text", "Share", "Delete")) { _, which ->
                when (which) {
                    0 -> {
                        val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("Transcript", transcript.text))
                        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, transcript.text)
                        }
                        startActivity(Intent.createChooser(intent, "Share transcript"))
                    }
                    2 -> {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                AppDatabase.get(this@HistoryActivity).transcriptDao().delete(transcript)
                            }
                            loadHistory()
                        }
                    }
                }
            }
            .show()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class TranscriptAdapter(
        private val onMenu:   (Transcript) -> Unit,
        private val onToggle: (Transcript) -> Unit
    ) : RecyclerView.Adapter<TranscriptAdapter.VH>() {

        private var items        = listOf<Transcript>()
        private var inSelectMode = false
        private var selected     = setOf<Int>()
        private val fmt          = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

        fun submitList(newItems: List<Transcript>) { items = newItems; notifyDataSetChanged() }
        fun setSelectMode(on: Boolean) { inSelectMode = on; notifyDataSetChanged() }
        fun setSelectedIds(ids: Set<Int>) { selected = ids.toSet(); notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_transcript, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) =
            holder.bind(items[position])

        override fun getItemCount() = items.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvLanguage:  TextView   = itemView.findViewById(R.id.tvLanguage)
            private val tvTimestamp: TextView   = itemView.findViewById(R.id.tvTimestamp)
            private val tvText:      TextView   = itemView.findViewById(R.id.tvText)
            private val tvDuration:  TextView   = itemView.findViewById(R.id.tvDuration)
            private val vDot:        View       = itemView.findViewById(R.id.vDurationDot)
            private val btnMore:     ImageButton = itemView.findViewById(R.id.btnMore)
            private val checkSelect: CheckBox   = itemView.findViewById(R.id.checkSelect)

            fun bind(t: Transcript) {
                tvLanguage.text  = t.languageName
                tvTimestamp.text = fmt.format(Date(t.timestamp))
                tvText.text      = t.text

                if (t.durationMs > 0) {
                    val secs = t.durationMs / 1000
                    tvDuration.text = "%d:%02d".format(secs / 60, secs % 60)
                    tvDuration.isVisible = true
                    vDot.isVisible = true
                } else {
                    tvDuration.isVisible = false
                    vDot.isVisible = false
                }

                checkSelect.isVisible = inSelectMode
                if (inSelectMode) checkSelect.isChecked = selected.contains(t.id)

                itemView.alpha = if (inSelectMode && selected.contains(t.id)) 1f else if (inSelectMode) 0.75f else 1f

                itemView.setOnClickListener {
                    if (inSelectMode) onToggle(t)
                    else {
                        val cb = itemView.context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("Transcript", t.text))
                        Toast.makeText(itemView.context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                }
                itemView.setOnLongClickListener {
                    if (!inSelectMode) { onMenu(t); true } else false
                }
                btnMore.setOnClickListener { onMenu(t) }
            }
        }
    }
}

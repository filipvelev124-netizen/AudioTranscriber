package com.audiotranscriber

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

    private var allItems = listOf<Transcript>()
    private lateinit var adapter: TranscriptAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_history)

        rvHistory = findViewById(R.id.rvHistory)
        tvEmpty   = findViewById(R.id.tvEmpty)
        etSearch  = findViewById(R.id.etSearch)
        bottomNav = findViewById(R.id.bottomNav)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<android.widget.Button>(R.id.btnClearAll)
            .setOnClickListener { confirmClearAll() }

        adapter = TranscriptAdapter(onMenu = { showItemMenu(it) })
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = adapter

        setupBottomNav()
        setupSearch()
        loadHistory()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    // ── Bottom nav ──────────────────────────────────────────────────────────

    private fun setupBottomNav() {
        bottomNav.selectedItemId = R.id.nav_history
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_transcribe -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
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

    // ── Search ──────────────────────────────────────────────────────────────

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                filterItems(query)
            }
        })
    }

    private fun filterItems(query: String) {
        val filtered = if (query.isEmpty()) {
            allItems
        } else {
            val q = query.lowercase()
            allItems.filter { it.text.lowercase().contains(q) || it.languageName.lowercase().contains(q) }
        }
        adapter.submitList(filtered)
        showEmptyState(filtered.isEmpty())
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
            .setItems(arrayOf("Copy text", "Delete")) { _, which ->
                when (which) {
                    0 -> {
                        val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("Transcript", transcript.text))
                        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
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
        private val onMenu: (Transcript) -> Unit
    ) : RecyclerView.Adapter<TranscriptAdapter.VH>() {

        private var items = listOf<Transcript>()
        private val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

        fun submitList(newItems: List<Transcript>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_transcript, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvLangInitial: TextView  = itemView.findViewById(R.id.tvLangInitial)
            private val tvLanguage: TextView     = itemView.findViewById(R.id.tvLanguage)
            private val tvTimestamp: TextView    = itemView.findViewById(R.id.tvTimestamp)
            private val tvText: TextView         = itemView.findViewById(R.id.tvText)
            private val btnMore: ImageButton     = itemView.findViewById(R.id.btnMore)

            fun bind(t: Transcript) {
                tvLangInitial.text = t.languageName.firstOrNull()?.uppercaseChar()?.toString() ?: "T"
                tvLanguage.text    = t.languageName
                tvTimestamp.text   = fmt.format(Date(t.timestamp))
                tvText.text        = t.text
                btnMore.setOnClickListener { onMenu(t) }
                itemView.setOnLongClickListener { onMenu(t); true }
                itemView.setOnClickListener {
                    val cb = itemView.context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("Transcript", t.text))
                    Toast.makeText(itemView.context, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

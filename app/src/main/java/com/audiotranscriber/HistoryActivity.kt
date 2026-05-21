package com.audiotranscriber

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private lateinit var adapter: TranscriptAdapter
    private lateinit var rvHistory: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Transcript History"

        rvHistory = findViewById(R.id.rvHistory)
        tvEmpty   = findViewById(R.id.tvEmpty)
        val btnClear = findViewById<Button>(R.id.btnClearAll)

        adapter = TranscriptAdapter(
            onLongClick = { transcript -> showItemMenu(transcript) }
        )
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = adapter

        btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Delete all transcripts?")
                .setPositiveButton("Delete") { _, _ ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            AppDatabase.get(this@HistoryActivity).transcriptDao().deleteAll()
                        }
                        adapter.submitList(emptyList())
                        showEmptyState(true)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        loadHistory()
    }

    private fun loadHistory() {
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                AppDatabase.get(this@HistoryActivity).transcriptDao().getAll()
            }
            adapter.submitList(items)
            showEmptyState(items.isEmpty())
        }
    }

    private fun showEmptyState(empty: Boolean) {
        tvEmpty.isVisible   = empty
        rvHistory.isVisible = !empty
    }

    private fun showItemMenu(transcript: Transcript) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("📋 Copy text", "🗑 Delete")) { _, which ->
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

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class TranscriptAdapter(
        private val onLongClick: (Transcript) -> Unit
    ) : RecyclerView.Adapter<TranscriptAdapter.VH>() {

        private var items = listOf<Transcript>()
        private val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

        fun submitList(newItems: List<Transcript>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_transcript, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
            private val tvLanguage: TextView  = itemView.findViewById(R.id.tvLanguage)
            private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
            private val tvText: TextView      = itemView.findViewById(R.id.tvText)

            fun bind(t: Transcript) {
                tvLanguage.text  = t.languageName
                tvTimestamp.text = fmt.format(Date(t.timestamp))
                tvText.text      = t.text
                itemView.setOnLongClickListener { onLongClick(t); true }
            }
        }
    }
}

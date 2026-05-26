package com.audiotranscriber

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcripts")
data class Transcript(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val languageName: String,
    val text: String,
    val durationMs: Long = 0
)

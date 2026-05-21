package com.audiotranscriber

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TranscriptDao {
    @Insert
    suspend fun insert(t: Transcript)

    @Query("SELECT * FROM transcripts ORDER BY timestamp DESC")
    suspend fun getAll(): List<Transcript>

    @Query("DELETE FROM transcripts")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(t: Transcript)
}

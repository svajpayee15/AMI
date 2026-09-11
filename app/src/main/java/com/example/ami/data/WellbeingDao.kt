package com.example.ami.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WellbeingDao {

    /**
     * Replaces on conflict so re-answering for the same day corrects that day rather
     * than failing - see the primary key note on [WellbeingEntry].
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WellbeingEntry)

    /**
     * Inclusive on both ends, ascending. The trend builder needs the window *and* the
     * window before it to work out a direction, so callers ask for one span covering
     * both rather than making two round trips.
     */
    @Query(
        "SELECT * FROM wellbeing_entries WHERE epochDay BETWEEN :fromEpochDay AND :toEpochDay " +
            "ORDER BY epochDay ASC"
    )
    suspend fun between(fromEpochDay: Long, toEpochDay: Long): List<WellbeingEntry>

    @Query("SELECT * FROM wellbeing_entries WHERE epochDay = :epochDay")
    suspend fun forDay(epochDay: Long): WellbeingEntry?

    @Query("SELECT COUNT(*) FROM wellbeing_entries")
    suspend fun count(): Int
}

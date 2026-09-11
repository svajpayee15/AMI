package com.example.ami.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionLogDao {

    @Query("SELECT * FROM action_log ORDER BY timestampEpochMilli DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<ActionLogRecord>>

    @Insert
    suspend fun insert(record: ActionLogRecord): Long

    /**
     * Trims the log to the newest [keep] rows.
     *
     * The log grows one row per guided step forever otherwise, and nobody reviews a
     * navigation step from four months ago. Bounding it also bounds how much of the
     * user's activity sits on disk.
     */
    @Query("DELETE FROM action_log WHERE id NOT IN (SELECT id FROM action_log ORDER BY timestampEpochMilli DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM action_log")
    suspend fun clear()
}

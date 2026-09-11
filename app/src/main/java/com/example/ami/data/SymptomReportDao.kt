package com.example.ami.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SymptomReportDao {

    @Query("SELECT * FROM symptom_reports ORDER BY timestampEpochMilli DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<SymptomReport>>

    /**
     * Newest first, which is the order [com.example.ami.caretaker.SymptomDigest] reports
     * "last mentioned" from.
     */
    @Query(
        "SELECT * FROM symptom_reports WHERE timestampEpochMilli >= :sinceEpochMilli " +
            "ORDER BY timestampEpochMilli DESC"
    )
    suspend fun since(sinceEpochMilli: Long): List<SymptomReport>

    @Query("SELECT * FROM symptom_reports WHERE checkInId = :checkInId")
    suspend fun forCheckIn(checkInId: Long): List<SymptomReport>

    @Insert
    suspend fun insert(report: SymptomReport): Long

    @Insert
    suspend fun insertAll(reports: List<SymptomReport>)

    @Query("DELETE FROM symptom_reports")
    suspend fun clear()
}

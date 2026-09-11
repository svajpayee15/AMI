package com.example.ami.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckInDao {

    @Query("SELECT * FROM check_ins ORDER BY timestampEpochMilli DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<CheckInRecord>>

    @Query("SELECT * FROM check_ins WHERE medicineId = :medicineId ORDER BY timestampEpochMilli DESC LIMIT :limit")
    suspend fun recentFor(medicineId: Long, limit: Int = 10): List<CheckInRecord>

    /** Everything in a window, oldest first - the shape an adherence report wants. */
    @Query(
        "SELECT * FROM check_ins WHERE timestampEpochMilli >= :fromEpochMilli " +
            "AND timestampEpochMilli < :toEpochMilli ORDER BY timestampEpochMilli ASC"
    )
    suspend fun between(fromEpochMilli: Long, toEpochMilli: Long): List<CheckInRecord>

    @Insert
    suspend fun insert(record: CheckInRecord): Long
}

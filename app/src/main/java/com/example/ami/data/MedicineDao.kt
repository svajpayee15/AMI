package com.example.ami.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicineDao {

    @Transaction
    @Query("SELECT * FROM medicines ORDER BY name ASC")
    fun observeAllWithDoses(): Flow<List<MedicineWithDoses>>

    @Transaction
    @Query("SELECT * FROM medicines ORDER BY name ASC")
    suspend fun getAllWithDoses(): List<MedicineWithDoses>

    @Query("SELECT * FROM medicines WHERE id = :id")
    suspend fun getById(id: Long): Medicine?

    @Insert
    suspend fun insert(medicine: Medicine): Long

    @Update
    suspend fun update(medicine: Medicine)

    @Delete
    suspend fun delete(medicine: Medicine)

    /** Matched case-insensitively so "metformin" and "Metformin" aren't two medicines. */
    @Query("SELECT * FROM medicines WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): Medicine?
}

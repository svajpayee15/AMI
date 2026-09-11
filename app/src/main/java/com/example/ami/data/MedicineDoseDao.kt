package com.example.ami.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MedicineDoseDao {

    @Query("SELECT * FROM medicine_doses WHERE id = :id")
    suspend fun getById(id: Long): MedicineDose?

    @Query("SELECT * FROM medicine_doses ORDER BY time ASC")
    suspend fun getAll(): List<MedicineDose>

    @Query("SELECT * FROM medicine_doses WHERE medicineId = :medicineId ORDER BY time ASC")
    suspend fun getForMedicine(medicineId: Long): List<MedicineDose>

    @Query("SELECT COUNT(*) FROM medicine_doses")
    suspend fun count(): Int

    @Insert
    suspend fun insert(dose: MedicineDose): Long

    @Query("UPDATE medicine_doses SET lastTakenEpochDay = :epochDay WHERE id = :id")
    suspend fun markTaken(id: Long, epochDay: Long)

    @Delete
    suspend fun delete(dose: MedicineDose)
}

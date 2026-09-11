package com.example.ami

import android.content.Context
import com.example.ami.data.MedicineDose
import com.example.ami.data.MedicineWithDoses
import com.example.ami.data.MedicineRepository
import kotlinx.coroutines.flow.Flow

/**
 * Thin facade over MedicineRepository for medicine CRUD.
 * Wellness-check scheduling, retry/no-response handling and caregiver escalation
 * now live in the alarm-driven AmiWellnessService, which has an actual channel
 * (email via the backend) to escalate through.
 */
class AmiCareManager private constructor(private val repository: MedicineRepository) {

    fun observeMedicines(): Flow<List<MedicineWithDoses>> = repository.observeMedicines()

    /** Returns the new dose, or null when that medicine already has that time. */
    suspend fun addDose(name: String, time: String, dosage: String?): MedicineDose? =
        repository.addDose(name, time, dosage)

    suspend fun deleteDose(dose: MedicineDose) = repository.deleteDose(dose)

    companion object {
        @Volatile
        private var INSTANCE: AmiCareManager? = null

        fun getInstance(context: Context): AmiCareManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AmiCareManager(MedicineRepository.getInstance(context)).also { INSTANCE = it }
            }
        }
    }
}

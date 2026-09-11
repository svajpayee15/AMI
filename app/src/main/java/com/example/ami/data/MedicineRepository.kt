package com.example.ami.data

import android.content.Context
import com.example.ami.caretaker.SymptomExtractor
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

/** A dose together with the medicine it belongs to - what a check-in actually needs. */
data class ScheduledDose(val dose: MedicineDose, val medicine: Medicine) {
    val displayName: String
        get() = medicine.dosage?.takeIf { it.isNotBlank() }
            ?.let { "${medicine.name} ($it)" }
            ?: medicine.name
}

class MedicineRepository private constructor(
    private val dao: MedicineDao,
    private val doseDao: MedicineDoseDao,
    private val checkInDao: CheckInDao,
    private val actionLogDao: ActionLogDao,
    private val symptomDao: SymptomReportDao
) {

    // --- Medicines and doses -----------------------------------------------------

    fun observeMedicines(): Flow<List<MedicineWithDoses>> = dao.observeAllWithDoses()

    suspend fun getMedicines(): List<MedicineWithDoses> = dao.getAllWithDoses()

    suspend fun getAllDoses(): List<MedicineDose> = doseDao.getAll()

    suspend fun getMedicine(id: Long): Medicine? = dao.getById(id)

    suspend fun getScheduledDose(doseId: Long): ScheduledDose? {
        val dose = doseDao.getById(doseId) ?: return null
        val medicine = dao.getById(dose.medicineId) ?: return null
        return ScheduledDose(dose, medicine)
    }

    /**
     * Adds [time] to the named medicine, creating the medicine if it is new.
     *
     * Folding by name is what makes "add Metformin at 08:00" then "add Metformin at
     * 20:00" produce one medicine with two doses instead of two separate entries the
     * caregiver then has to reconcile. Returns the new dose, or null if that exact
     * time was already scheduled.
     */
    suspend fun addDose(name: String, time: String, dosage: String? = null): MedicineDose? {
        val existing = dao.findByName(name)
        val medicineId = when {
            existing == null -> dao.insert(Medicine(name = name, dosage = dosage))
            // A dosage typed on a later add updates the medicine rather than being lost.
            !dosage.isNullOrBlank() && dosage != existing.dosage -> {
                dao.update(existing.copy(dosage = dosage))
                existing.id
            }
            else -> existing.id
        }

        if (doseDao.getForMedicine(medicineId).any { it.time == time }) return null

        val dose = MedicineDose(medicineId = medicineId, time = time)
        return dose.copy(id = doseDao.insert(dose))
    }

    suspend fun markDoseTakenToday(doseId: Long) =
        doseDao.markTaken(doseId, LocalDate.now().toEpochDay())

    suspend fun deleteDose(dose: MedicineDose) {
        doseDao.delete(dose)
        // A medicine with no remaining times is no longer a schedule, just a name.
        if (doseDao.getForMedicine(dose.medicineId).isEmpty()) {
            dao.getById(dose.medicineId)?.let { dao.delete(it) }
        }
    }

    suspend fun deleteMedicine(medicine: Medicine) = dao.delete(medicine)

    suspend fun setDosage(medicineId: Long, dosage: String?) {
        dao.getById(medicineId)?.let { dao.update(it.copy(dosage = dosage?.takeIf { d -> d.isNotBlank() })) }
    }

    // --- Check-in history --------------------------------------------------------

    fun observeRecentCheckIns(): Flow<List<CheckInRecord>> = checkInDao.observeRecent()

    suspend fun checkInsBetween(fromEpochMilli: Long, toEpochMilli: Long): List<CheckInRecord> =
        checkInDao.between(fromEpochMilli, toEpochMilli)

    /** Newest first, which is the order EscalationPlan.consecutiveMisses expects. */
    suspend fun recentCheckInsFor(medicineId: Long, limit: Int = 10): List<CheckInRecord> =
        checkInDao.recentFor(medicineId, limit)

    suspend fun recordCheckIn(
        medicineId: Long,
        doseId: Long,
        medicineName: String,
        scheduledTime: String,
        outcome: String,
        wellbeingNote: String? = null
    ) = checkInDao.insert(
        CheckInRecord(
            medicineId = medicineId,
            doseId = doseId,
            medicineName = medicineName,
            scheduledTime = scheduledTime,
            outcome = outcome,
            wellbeingNote = wellbeingNote
        )
    )

    // --- Symptoms ----------------------------------------------------------------

    fun observeRecentSymptoms(): Flow<List<SymptomReport>> = symptomDao.observeRecent()

    /**
     * @param windowDays counted inclusively of today, matching
     *   [com.example.ami.caretaker.SymptomDigest.of] - the digest does its own calendar-day
     *   filtering, so this only has to be generous enough not to clip it.
     */
    suspend fun symptomsInLastDays(windowDays: Int): List<SymptomReport> {
        val since = LocalDate.now()
            .minusDays((windowDays - 1).coerceAtLeast(0).toLong())
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return symptomDao.since(since)
    }

    /** Symptoms heard on one call, written together once the check-in row has an id. */
    suspend fun recordSymptoms(
        checkInId: Long,
        detected: List<SymptomExtractor.Detected>,
        timestampEpochMilli: Long = System.currentTimeMillis()
    ) {
        if (detected.isEmpty()) return
        symptomDao.insertAll(
            detected.map {
                SymptomReport(
                    checkInId = checkInId,
                    symptomKey = it.symptom.key,
                    severity = it.severity.key,
                    rawText = it.heard,
                    timestampEpochMilli = timestampEpochMilli
                )
            }
        )
    }

    // --- Guided-navigation log ---------------------------------------------------

    fun observeActionLog(): Flow<List<ActionLogRecord>> = actionLogDao.observeRecent()

    suspend fun recordAction(
        goal: String,
        action: String,
        spokenText: String? = null,
        packageName: String? = null,
        outcome: String = ActionLogRecord.OUTCOME_OK
    ) {
        actionLogDao.insert(
            ActionLogRecord(
                goal = goal,
                action = action,
                spokenText = spokenText,
                packageName = packageName,
                outcome = outcome
            )
        )
        actionLogDao.trimTo(ACTION_LOG_LIMIT)
    }

    suspend fun clearActionLog() = actionLogDao.clear()

    companion object {
        /** Roughly a month of heavy use; see ActionLogDao.trimTo. */
        private const val ACTION_LOG_LIMIT = 500

        @Volatile
        private var INSTANCE: MedicineRepository? = null

        fun getInstance(context: Context): MedicineRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AmiDatabase.getInstance(context).let { db ->
                    MedicineRepository(
                        db.medicineDao(), db.medicineDoseDao(), db.checkInDao(),
                        db.actionLogDao(), db.symptomReportDao()
                    )
                }.also { INSTANCE = it }
            }
        }
    }
}

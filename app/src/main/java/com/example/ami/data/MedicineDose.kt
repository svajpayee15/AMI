package com.example.ami.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * One scheduled time for a medicine, and whether it has been taken today.
 *
 * "Taken" is tracked per dose rather than per medicine: with a morning and an evening
 * dose, marking the medicine taken at 08:00 would suppress the 20:00 check-in, which
 * is exactly the missed dose the app exists to catch.
 */
@Entity(
    tableName = "medicine_doses",
    foreignKeys = [
        ForeignKey(
            entity = Medicine::class,
            parentColumns = ["id"],
            childColumns = ["medicineId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("medicineId")]
)
data class MedicineDose(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    /** Zero-padded 24-hour "HH:MM"; MedicineAlarmScheduler parses it as such. */
    val time: String,
    val lastTakenEpochDay: Long? = null
) {
    val isTakenToday: Boolean
        get() = lastTakenEpochDay == LocalDate.now().toEpochDay()
}

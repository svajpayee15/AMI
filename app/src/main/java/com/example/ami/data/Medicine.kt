package com.example.ami.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One prescribed medicine.
 *
 * The time of day used to live here, which capped the app at a single dose per
 * medicine per day - a real problem for the target user, whose prescriptions
 * routinely read "twice daily with food". Times now live in [MedicineDose], one row
 * per dose, so "Metformin at 08:00 and 20:00" is one medicine with two doses rather
 * than two unrelated medicines that happen to share a name.
 */
@Entity(tableName = "medicines")
data class Medicine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /**
     * Free text, spoken aloud during the check-in: "one tablet, with food".
     * Null when the caregiver didn't record one.
     */
    val dosage: String? = null
)

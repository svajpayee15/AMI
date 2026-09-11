package com.example.ami.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The outcome of one medicine check-in call.
 *
 * This is what makes the call more than an alarm: it records whether the dose was
 * actually confirmed and, when the user said something about how they were feeling,
 * their own words - which is the part a caregiver actually wants to read.
 */
@Entity(tableName = "check_ins")
data class CheckInRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    /**
     * Which dose of the day this was. 0 for rows written before doses existed, which
     * is why adherence is counted per record rather than joined back to the schedule.
     */
    val doseId: Long = 0,
    val medicineName: String,
    /** The scheduled "HH:MM" this check-in was for, so a report can say which dose. */
    val scheduledTime: String = "",
    val outcome: String,
    /** Free text, in the user's own words. Null when they never got as far as answering. */
    val wellbeingNote: String? = null,
    val timestampEpochMilli: Long = System.currentTimeMillis()
) {
    val wasTaken: Boolean get() = outcome == OUTCOME_TAKEN

    companion object {
        /** Confirmed they had taken it. */
        const val OUTCOME_TAKEN = "TAKEN"

        /** Answered, but said they had not taken it. */
        const val OUTCOME_NOT_TAKEN = "NOT_TAKEN"

        /** Rang out - nobody picked up. */
        const val OUTCOME_NO_ANSWER = "NO_ANSWER"

        /** Actively dismissed the call. */
        const val OUTCOME_DECLINED = "DECLINED"
    }
}

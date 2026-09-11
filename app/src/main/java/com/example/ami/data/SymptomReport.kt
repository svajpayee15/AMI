package com.example.ami.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.ami.caretaker.Symptom
import com.example.ami.caretaker.SymptomSeverity

/**
 * One health complaint, as it was mentioned on one call.
 *
 * Stored as a row per mention rather than a running tally per symptom: "a headache four
 * times this week" and "a headache once" are the same tally after the fourth write if you
 * only keep a count, and the dates are the whole point of the caretaker report.
 *
 * [rawText] keeps the person's own words alongside the matched key. The key is what gets
 * counted; the words are what a caregiver actually wants to read, and what makes a wrong
 * match visible rather than silent.
 *
 * The symptom and severity are stored as their string keys, not as enum ordinals - an
 * ordinal written to disk breaks the moment someone inserts an entry into the middle of
 * [Symptom], which is a table full of silently wrong history.
 */
@Entity(tableName = "symptom_reports")
data class SymptomReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The check-in this came out of. 0 when it was recorded outside a call. */
    val checkInId: Long = 0,
    /** A [Symptom.key]. Rows whose key no longer resolves are skipped, never crashed on. */
    val symptomKey: String,
    /** A [SymptomSeverity.key]. */
    val severity: String = SymptomSeverity.UNKNOWN.key,
    /** What they actually said. */
    val rawText: String = "",
    val timestampEpochMilli: Long = System.currentTimeMillis()
) {
    /** Null when the stored key is from a newer build than this one knows about. */
    val symptom: Symptom? get() = Symptom.forKey(symptomKey)

    val severityLevel: SymptomSeverity get() = SymptomSeverity.forKey(severity)

    val isRedFlag: Boolean get() = symptom?.redFlag == true
}

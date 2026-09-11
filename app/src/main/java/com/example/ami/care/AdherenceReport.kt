package com.example.ami.care

import com.example.ami.data.CheckInRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What actually happened at every check-in over a window, aggregated for a caregiver.
 *
 * Pure arithmetic over the stored history, with no Android or database dependency, so
 * the numbers a caregiver is emailed can be tested directly. That matters more here
 * than in most reports: an adherence figure that silently over-counts would tell a
 * family their parent is taking medicine they are not.
 *
 * Only a *confirmed* dose counts as taken. A check-in nobody answered is counted as
 * missed, not excluded - the alternative flatters the number exactly when something is
 * going wrong.
 */
data class AdherenceReport(
    val from: LocalDate,
    val to: LocalDate,
    val taken: Int,
    val notTaken: Int,
    val noAnswer: Int,
    val declined: Int,
    val perMedicine: List<MedicineAdherence>,
    val days: List<DayAdherence>,
    val wellbeingNotes: List<WellbeingNote>
) {
    val total: Int get() = taken + notTaken + noAnswer + declined

    val missed: Int get() = notTaken + noAnswer + declined

    /** Null rather than 0 when nothing was scheduled - "0%" would read as a crisis. */
    val adherencePercent: Int?
        get() = if (total == 0) null else Math.round(taken * 100f / total)

    /** Consecutive most-recent days on which every check-in was missed. */
    val currentMissedDayStreak: Int
        get() = days.reversed().takeWhile { it.total > 0 && it.taken == 0 }.count()

    val hasAnyActivity: Boolean get() = total > 0

    data class MedicineAdherence(
        val medicineName: String,
        val taken: Int,
        val missed: Int
    ) {
        val total: Int get() = taken + missed
        val adherencePercent: Int?
            get() = if (total == 0) null else Math.round(taken * 100f / total)
    }

    data class DayAdherence(val date: LocalDate, val taken: Int, val missed: Int) {
        val total: Int get() = taken + missed
    }

    data class WellbeingNote(
        val date: LocalDate,
        val medicineName: String,
        val note: String
    )

    companion object {

        /**
         * @param records every check-in in the window, in any order
         * @param from first day of the window, inclusive
         * @param to last day of the window, inclusive
         */
        fun of(
            records: List<CheckInRecord>,
            from: LocalDate,
            to: LocalDate,
            zone: ZoneId = ZoneId.systemDefault()
        ): AdherenceReport {
            val inWindow = records.filter { record ->
                val day = dayOf(record, zone)
                !day.isBefore(from) && !day.isAfter(to)
            }

            val perMedicine = inWindow
                .groupBy { it.medicineName }
                .map { (name, rows) ->
                    MedicineAdherence(
                        medicineName = name,
                        taken = rows.count { it.wasTaken },
                        missed = rows.count { !it.wasTaken }
                    )
                }
                .sortedBy { it.medicineName.lowercase() }

            val byDay = inWindow.groupBy { dayOf(it, zone) }
            // Every day in the window appears, including ones with nothing recorded,
            // so a run of silent days is visible rather than collapsed away.
            val days = generateSequence(from) { day ->
                if (day.isBefore(to)) day.plusDays(1) else null
            }.map { day ->
                val rows = byDay[day].orEmpty()
                DayAdherence(day, rows.count { it.wasTaken }, rows.count { !it.wasTaken })
            }.toList()

            val notes = inWindow
                .filter { !it.wellbeingNote.isNullOrBlank() }
                .sortedByDescending { it.timestampEpochMilli }
                .map {
                    WellbeingNote(dayOf(it, zone), it.medicineName, it.wellbeingNote!!.trim())
                }

            return AdherenceReport(
                from = from,
                to = to,
                taken = inWindow.count { it.outcome == CheckInRecord.OUTCOME_TAKEN },
                notTaken = inWindow.count { it.outcome == CheckInRecord.OUTCOME_NOT_TAKEN },
                noAnswer = inWindow.count { it.outcome == CheckInRecord.OUTCOME_NO_ANSWER },
                declined = inWindow.count { it.outcome == CheckInRecord.OUTCOME_DECLINED },
                perMedicine = perMedicine,
                days = days,
                wellbeingNotes = notes
            )
        }

        private fun dayOf(record: CheckInRecord, zone: ZoneId): LocalDate =
            Instant.ofEpochMilli(record.timestampEpochMilli).atZone(zone).toLocalDate()
    }
}

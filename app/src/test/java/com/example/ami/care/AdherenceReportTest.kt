package com.example.ami.care

import com.example.ami.data.CheckInRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class AdherenceReportTest {

    private val zone = ZoneId.of("UTC")
    private val monday = LocalDate.of(2026, 9, 7)
    private val sunday = LocalDate.of(2026, 9, 13)

    private fun record(
        day: LocalDate,
        outcome: String,
        name: String = "Metformin",
        note: String? = null,
        hour: Int = 8
    ) = CheckInRecord(
        medicineId = 1,
        doseId = 1,
        medicineName = name,
        scheduledTime = "%02d:00".format(hour),
        outcome = outcome,
        wellbeingNote = note,
        timestampEpochMilli = day.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()
    )

    @Test
    fun `counts every outcome and rounds the percentage`() {
        val report = AdherenceReport.of(
            listOf(
                record(monday, CheckInRecord.OUTCOME_TAKEN),
                record(monday.plusDays(1), CheckInRecord.OUTCOME_TAKEN),
                record(monday.plusDays(2), CheckInRecord.OUTCOME_NOT_TAKEN),
                record(monday.plusDays(3), CheckInRecord.OUTCOME_NO_ANSWER),
                record(monday.plusDays(4), CheckInRecord.OUTCOME_DECLINED)
            ),
            monday, sunday, zone
        )

        assertEquals(5, report.total)
        assertEquals(2, report.taken)
        assertEquals(3, report.missed)
        assertEquals(1, report.notTaken)
        assertEquals(1, report.noAnswer)
        assertEquals(1, report.declined)
        assertEquals(40, report.adherencePercent)
    }

    /**
     * The whole point of the report: an unanswered check-in is a missed dose, not a
     * data gap. Excluding it would show 100% adherence for a week nobody answered.
     */
    @Test
    fun `unanswered check-ins count against adherence`() {
        val report = AdherenceReport.of(
            listOf(
                record(monday, CheckInRecord.OUTCOME_TAKEN),
                record(monday.plusDays(1), CheckInRecord.OUTCOME_NO_ANSWER)
            ),
            monday, sunday, zone
        )
        assertEquals(50, report.adherencePercent)
    }

    @Test
    fun `an empty week reports no percentage rather than zero`() {
        val report = AdherenceReport.of(emptyList(), monday, sunday, zone)
        assertNull(report.adherencePercent)
        assertEquals(false, report.hasAnyActivity)
        assertEquals(7, report.days.size)
    }

    @Test
    fun `records outside the window are ignored`() {
        val report = AdherenceReport.of(
            listOf(
                record(monday.minusDays(1), CheckInRecord.OUTCOME_TAKEN),
                record(sunday.plusDays(1), CheckInRecord.OUTCOME_TAKEN),
                record(monday, CheckInRecord.OUTCOME_NOT_TAKEN)
            ),
            monday, sunday, zone
        )
        assertEquals(1, report.total)
        assertEquals(0, report.taken)
    }

    @Test
    fun `splits by medicine in name order`() {
        val report = AdherenceReport.of(
            listOf(
                record(monday, CheckInRecord.OUTCOME_TAKEN, name = "Warfarin"),
                record(monday, CheckInRecord.OUTCOME_TAKEN, name = "Aspirin", hour = 9),
                record(monday.plusDays(1), CheckInRecord.OUTCOME_NO_ANSWER, name = "Aspirin")
            ),
            monday, sunday, zone
        )
        assertEquals(listOf("Aspirin", "Warfarin"), report.perMedicine.map { it.medicineName })
        assertEquals(50, report.perMedicine.first().adherencePercent)
        assertEquals(100, report.perMedicine.last().adherencePercent)
    }

    @Test
    fun `counts a run of fully missed days ending today`() {
        val report = AdherenceReport.of(
            listOf(
                record(sunday.minusDays(4), CheckInRecord.OUTCOME_TAKEN),
                record(sunday.minusDays(1), CheckInRecord.OUTCOME_NO_ANSWER),
                record(sunday, CheckInRecord.OUTCOME_NOT_TAKEN)
            ),
            monday, sunday, zone
        )
        assertEquals(2, report.currentMissedDayStreak)
    }

    @Test
    fun `a confirmed dose ends the missed-day streak`() {
        val report = AdherenceReport.of(
            listOf(
                record(sunday.minusDays(1), CheckInRecord.OUTCOME_NO_ANSWER),
                record(sunday, CheckInRecord.OUTCOME_TAKEN)
            ),
            monday, sunday, zone
        )
        assertEquals(0, report.currentMissedDayStreak)
    }

    @Test
    fun `keeps wellbeing notes newest first`() {
        val report = AdherenceReport.of(
            listOf(
                record(monday, CheckInRecord.OUTCOME_TAKEN, note = "a bit tired"),
                record(monday.plusDays(2), CheckInRecord.OUTCOME_TAKEN, note = "much better"),
                record(monday.plusDays(1), CheckInRecord.OUTCOME_TAKEN, note = "   ")
            ),
            monday, sunday, zone
        )
        assertEquals(listOf("much better", "a bit tired"), report.wellbeingNotes.map { it.note })
    }

    @Test
    fun `every day of the window is present even with no check-ins`() {
        val report = AdherenceReport.of(
            listOf(record(monday.plusDays(3), CheckInRecord.OUTCOME_TAKEN)),
            monday, sunday, zone
        )
        assertEquals(7, report.days.size)
        assertEquals(monday, report.days.first().date)
        assertEquals(sunday, report.days.last().date)
        assertTrue(report.days.count { it.total == 0 } == 6)
    }
}

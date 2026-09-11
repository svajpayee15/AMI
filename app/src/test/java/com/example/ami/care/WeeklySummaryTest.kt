package com.example.ami.care

import com.example.ami.data.CheckInRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class WeeklySummaryTest {

    private val zone = ZoneId.of("UTC")
    private val monday = LocalDate.of(2026, 9, 7)
    private val sunday = LocalDate.of(2026, 9, 13)

    private fun record(day: LocalDate, outcome: String, note: String? = null) = CheckInRecord(
        medicineId = 1, doseId = 1, medicineName = "Metformin", scheduledTime = "08:00",
        outcome = outcome, wellbeingNote = note,
        timestampEpochMilli = day.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
    )

    private fun reportOf(vararg records: CheckInRecord) =
        AdherenceReport.of(records.toList(), monday, sunday, zone)

    @Test
    fun `the window is the last completed Monday to Sunday`() {
        // Wednesday 16 Sept 2026 -> the week that closed on Sunday 13 Sept.
        val (from, to) = WeeklySummary.windowFor(LocalDate.of(2026, 9, 16))
        assertEquals(monday, from)
        assertEquals(sunday, to)
    }

    @Test
    fun `on a Sunday the window is the week ending that day`() {
        val (from, to) = WeeklySummary.windowFor(sunday)
        assertEquals(monday, from)
        assertEquals(sunday, to)
    }

    @Test
    fun `a good week leads with reassurance`() {
        val report = reportOf(
            record(monday, CheckInRecord.OUTCOME_TAKEN),
            record(monday.plusDays(1), CheckInRecord.OUTCOME_TAKEN),
            record(monday.plusDays(2), CheckInRecord.OUTCOME_TAKEN)
        )
        val body = WeeklySummary.body(report, "Mum")
        assertTrue(body.lineSequence().first().contains("Nothing needs your attention"))
        assertTrue(WeeklySummary.subject(report, "Mum").contains("100% on track"))
    }

    @Test
    fun `a run of missed days leads with the run`() {
        val report = reportOf(
            record(sunday.minusDays(1), CheckInRecord.OUTCOME_NO_ANSWER),
            record(sunday, CheckInRecord.OUTCOME_NO_ANSWER)
        )
        assertTrue(WeeklySummary.body(report, "Mum").lineSequence().first().contains("last 2 days"))
    }

    @Test
    fun `an empty week says so instead of reporting zero percent`() {
        val report = reportOf()
        val body = WeeklySummary.body(report, "Mum")
        assertTrue(body.contains("nothing to report"))
        assertFalse(body.contains("0%"))
        assertTrue(WeeklySummary.subject(report, "Mum").contains("no check-ins"))
    }

    @Test
    fun `wellbeing notes are quoted in the body`() {
        val report = reportOf(record(monday, CheckInRecord.OUTCOME_TAKEN, note = "knee is sore"))
        assertTrue(WeeklySummary.body(report, "Mum").contains("\"knee is sore\""))
    }

    @Test
    fun `vitals are included only when supplied`() {
        val report = reportOf(record(monday, CheckInRecord.OUTCOME_TAKEN))
        assertTrue(WeeklySummary.body(report, "Mum", "Steps 4200").contains("Steps 4200"))
        assertFalse(WeeklySummary.body(report, "Mum", "  ").contains("Latest readings"))
    }

    @Test
    fun `every summary carries the not-medical-advice footer`() {
        assertTrue(WeeklySummary.body(reportOf(), "Mum").contains("not medical advice"))
    }

    /**
     * Doze can deliver a weekly alarm late or coalesce it; two summaries an hour apart
     * teach the reader to ignore them.
     */
    @Test
    fun `a summary is not due again within the same week`() {
        val now = sunday.atTime(LocalTime.of(18, 0)).atZone(zone).toInstant()
        val sentYesterday = sunday.minusDays(1).atTime(LocalTime.NOON).atZone(zone).toInstant()
        assertFalse(WeeklySummary.isDue(sentYesterday.toEpochMilli(), now, zone))
    }

    @Test
    fun `a summary is due after a full week and on the very first run`() {
        val now = sunday.atTime(LocalTime.of(18, 0)).atZone(zone).toInstant()
        val sentLastWeek = sunday.minusDays(7).atTime(LocalTime.NOON).atZone(zone).toInstant()
        assertTrue(WeeklySummary.isDue(sentLastWeek.toEpochMilli(), now, zone))
        assertTrue(WeeklySummary.isDue(0L, Instant.now(), zone))
    }
}

package com.example.ami.caretaker

import com.example.ami.data.SymptomReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SymptomDigestTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 9, 11)
    private val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun report(
        symptom: Symptom,
        daysAgo: Int = 0,
        severity: SymptomSeverity = SymptomSeverity.UNKNOWN,
        hour: Int = 9,
        words: String = "it hurts"
    ) = SymptomReport(
        symptomKey = symptom.key,
        severity = severity.key,
        rawText = words,
        timestampEpochMilli = today.minusDays(daysAgo.toLong())
            .atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    )

    private fun digestOf(vararg rows: SymptomReport) =
        SymptomDigest.of(rows.toList(), windowDays = 7, nowEpochMilli = now, zone = zone)

    @Test
    fun `nothing reported is an empty digest, not a crash`() {
        val digest = SymptomDigest.of(emptyList(), nowEpochMilli = now, zone = zone)
        assertTrue(digest.isEmpty)
        assertFalse(digest.hasRedFlag)
        assertTrue(digest.describe().contains("no symptoms"))
    }

    // --- Counting ---------------------------------------------------------------------

    @Test
    fun `mentions and days affected are counted separately`() {
        // Three mentions, but two of them on the same afternoon.
        val digest = digestOf(
            report(Symptom.HEADACHE, daysAgo = 0, hour = 9),
            report(Symptom.HEADACHE, daysAgo = 0, hour = 20),
            report(Symptom.HEADACHE, daysAgo = 2)
        )
        val entry = digest.entries.single()
        assertEquals(3, entry.mentions)
        assertEquals(2, entry.daysAffected)
    }

    @Test
    fun `recurring is decided by days, not by mentions`() {
        // Four mentions crammed into two days is a bad couple of days, not a pattern.
        val burst = digestOf(
            report(Symptom.HEADACHE, daysAgo = 0, hour = 8),
            report(Symptom.HEADACHE, daysAgo = 0, hour = 14),
            report(Symptom.HEADACHE, daysAgo = 1, hour = 8),
            report(Symptom.HEADACHE, daysAgo = 1, hour = 20)
        )
        assertEquals(4, burst.entries.single().mentions)
        assertFalse(burst.entries.single().isRecurring)

        val spread = digestOf(
            report(Symptom.HEADACHE, daysAgo = 0),
            report(Symptom.HEADACHE, daysAgo = 2),
            report(Symptom.HEADACHE, daysAgo = 4)
        )
        assertTrue(spread.entries.single().isRecurring)
    }

    @Test
    fun `the worst severity reported wins`() {
        val digest = digestOf(
            report(Symptom.HEADACHE, daysAgo = 0, severity = SymptomSeverity.MILD),
            report(Symptom.HEADACHE, daysAgo = 1, severity = SymptomSeverity.SEVERE),
            report(Symptom.HEADACHE, daysAgo = 2, severity = SymptomSeverity.MODERATE)
        )
        assertEquals(SymptomSeverity.SEVERE, digest.entries.single().worstSeverity)
    }

    @Test
    fun `the most recent words are the ones quoted`() {
        val digest = digestOf(
            report(Symptom.HEADACHE, daysAgo = 3, words = "old words"),
            report(Symptom.HEADACHE, daysAgo = 0, words = "new words")
        )
        assertEquals("new words", digest.entries.single().lastWords)
    }

    // --- The window -------------------------------------------------------------------

    @Test
    fun `the window is inclusive of today and counted in calendar days`() {
        val digest = digestOf(
            report(Symptom.HEADACHE, daysAgo = 6),  // first day of a 7-day window
            report(Symptom.FEVER, daysAgo = 7)      // one day too old
        )
        assertEquals(listOf(Symptom.HEADACHE), digest.entries.map { it.symptom })
    }

    @Test
    fun `an early mention today still counts as today`() {
        // 00:30 today is 35 hours before "now", which a rolling-168-hours window at the
        // wrong moment would keep but a 6-day-ago cutoff computed in hours would drop.
        val digest = digestOf(report(Symptom.HEADACHE, daysAgo = 6, hour = 0))
        assertEquals(1, digest.entries.size)
    }

    @Test
    fun `a zero or negative window reports nothing`() {
        val digest = SymptomDigest.of(
            listOf(report(Symptom.HEADACHE)),
            windowDays = 0,
            nowEpochMilli = now,
            zone = zone
        )
        assertTrue(digest.isEmpty)
    }

    // --- Ordering and separation ------------------------------------------------------

    @Test
    fun `red flags sort first even when mentioned least`() {
        val digest = digestOf(
            report(Symptom.HEADACHE, daysAgo = 0),
            report(Symptom.HEADACHE, daysAgo = 1),
            report(Symptom.HEADACHE, daysAgo = 2),
            report(Symptom.CHEST_PAIN, daysAgo = 1)
        )
        assertEquals(Symptom.CHEST_PAIN, digest.entries.first().symptom)
        assertTrue(digest.hasRedFlag)
    }

    @Test
    fun `red flags are kept out of the advisable list`() {
        val digest = digestOf(
            report(Symptom.CHEST_PAIN, daysAgo = 0),
            report(Symptom.HEADACHE, daysAgo = 0)
        )
        assertEquals(listOf(Symptom.HEADACHE), digest.advisable.map { it.symptom })
        assertEquals(listOf(Symptom.CHEST_PAIN), digest.redFlags.map { it.symptom })
    }

    @Test
    fun `recurring excludes red flags, which are never a pattern to advise on`() {
        val digest = digestOf(
            report(Symptom.CHEST_PAIN, daysAgo = 0),
            report(Symptom.CHEST_PAIN, daysAgo = 1),
            report(Symptom.CHEST_PAIN, daysAgo = 2)
        )
        assertTrue(digest.entries.single().isRecurring)
        assertTrue(digest.recurring.isEmpty())
    }

    @Test
    fun `the thing happening on more days outranks the thing mentioned more often`() {
        val digest = digestOf(
            report(Symptom.FATIGUE, daysAgo = 0, hour = 8),
            report(Symptom.FATIGUE, daysAgo = 0, hour = 12),
            report(Symptom.FATIGUE, daysAgo = 0, hour = 18),
            report(Symptom.HEADACHE, daysAgo = 0),
            report(Symptom.HEADACHE, daysAgo = 2)
        )
        assertEquals(Symptom.HEADACHE, digest.entries.first().symptom)
    }

    // --- Robustness -------------------------------------------------------------------

    @Test
    fun `a row from a newer build is skipped rather than crashed on`() {
        val digest = SymptomDigest.of(
            listOf(
                report(Symptom.HEADACHE),
                SymptomReport(symptomKey = "hiccups_from_the_future", timestampEpochMilli = now)
            ),
            nowEpochMilli = now,
            zone = zone
        )
        assertEquals(listOf(Symptom.HEADACHE), digest.entries.map { it.symptom })
    }

    @Test
    fun `describe names the symptom, the counts and their words`() {
        val digest = digestOf(
            report(Symptom.HEADACHE, daysAgo = 0, severity = SymptomSeverity.SEVERE, words = "head is splitting")
        )
        val described = digest.describe()
        assertTrue(described.contains("headache"))
        assertTrue(described.contains("1 mention"))
        assertTrue(described.contains("severe"))
        assertTrue(described.contains("head is splitting"))
    }
}

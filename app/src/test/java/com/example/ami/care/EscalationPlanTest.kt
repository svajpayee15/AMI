package com.example.ami.care

import com.example.ami.data.CaregiverContact
import com.example.ami.data.CheckInRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscalationPlanTest {

    private val chain = listOf(
        CaregiverContact("Daughter", "daughter@example.com"),
        CaregiverContact("Son", "son@example.com"),
        CaregiverContact("Neighbour", "neighbour@example.com")
    )

    private fun checkIn(outcome: String) = CheckInRecord(
        medicineId = 1, doseId = 1, medicineName = "Metformin",
        scheduledTime = "08:00", outcome = outcome
    )

    @Test
    fun `only the primary hears about an ordinary missed dose`() {
        val recipients = EscalationPlan.recipientsFor(
            chain, CheckInRecord.OUTCOME_NOT_TAKEN, consecutiveMisses = 1
        )
        assertEquals(listOf("daughter@example.com"), recipients.map { it.email })
    }

    /**
     * Nobody picking up is the one case where the primary contact may also be the
     * person who cannot reach them, so the second contact is pulled in at once.
     */
    @Test
    fun `nobody answering pulls in the second contact immediately`() {
        val recipients = EscalationPlan.recipientsFor(
            chain, CheckInRecord.OUTCOME_NO_ANSWER, consecutiveMisses = 1
        )
        assertEquals(listOf("daughter@example.com", "son@example.com"), recipients.map { it.email })
    }

    @Test
    fun `a declined call is not treated as unreachable`() {
        val recipients = EscalationPlan.recipientsFor(
            chain, CheckInRecord.OUTCOME_DECLINED, consecutiveMisses = 1
        )
        assertEquals(1, recipients.size)
    }

    @Test
    fun `a long run of misses eventually reaches everyone`() {
        val recipients = EscalationPlan.recipientsFor(
            chain, CheckInRecord.OUTCOME_NOT_TAKEN, consecutiveMisses = 3
        )
        assertEquals(3, recipients.size)
    }

    @Test
    fun `an empty chain produces no recipients rather than throwing`() {
        assertTrue(
            EscalationPlan.recipientsFor(emptyList(), CheckInRecord.OUTCOME_NO_ANSWER, 5).isEmpty()
        )
    }

    @Test
    fun `counts only the unbroken run of misses at the top of the history`() {
        val history = listOf(
            checkIn(CheckInRecord.OUTCOME_NO_ANSWER),
            checkIn(CheckInRecord.OUTCOME_NOT_TAKEN),
            checkIn(CheckInRecord.OUTCOME_TAKEN),
            checkIn(CheckInRecord.OUTCOME_NO_ANSWER)
        )
        assertEquals(2, EscalationPlan.consecutiveMisses(history))
    }

    @Test
    fun `a confirmed most-recent dose means no run at all`() {
        assertEquals(0, EscalationPlan.consecutiveMisses(listOf(checkIn(CheckInRecord.OUTCOME_TAKEN))))
        assertEquals(0, EscalationPlan.consecutiveMisses(emptyList()))
    }

    @Test
    fun `sustained silence matches the three checks onboarding promises`() {
        assertFalse(EscalationPlan.isSustainedSilence(2))
        assertTrue(EscalationPlan.isSustainedSilence(3))
    }
}

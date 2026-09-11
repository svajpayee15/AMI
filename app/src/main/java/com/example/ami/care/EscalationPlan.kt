package com.example.ami.care

import com.example.ami.data.CaregiverContact
import com.example.ami.data.CheckInRecord

/**
 * Who gets told about a missed check-in, and how loudly.
 *
 * The chain widens with the seriousness of the situation rather than notifying
 * everybody every time:
 *
 *  - The **primary** contact hears about every missed dose. They are the one person
 *    who has agreed to act on these.
 *  - The **second** contact is pulled in immediately when nobody answered the phone
 *    at all, because that is the case where the primary contact may also be the one
 *    who can't reach them.
 *  - Each **further** contact joins once the run of consecutive misses is long enough
 *    to reach their position in the chain, so a three-day silence eventually reaches
 *    everyone.
 *
 * Pure, so the ladder can be tested without a device, a network or a mailbox.
 */
object EscalationPlan {

    fun recipientsFor(
        contacts: List<CaregiverContact>,
        outcome: String,
        consecutiveMisses: Int
    ): List<CaregiverContact> {
        if (contacts.isEmpty()) return emptyList()
        val nobodyAnswered = outcome == CheckInRecord.OUTCOME_NO_ANSWER

        return contacts.filterIndexed { index, _ ->
            when {
                index == 0 -> true
                index == 1 && nobodyAnswered -> true
                else -> consecutiveMisses >= index + 1
            }
        }
    }

    /**
     * How many check-ins in a row, ending with the most recent, were not confirmed.
     *
     * @param historyNewestFirst check-ins ordered newest first, as the DAO returns them
     */
    fun consecutiveMisses(historyNewestFirst: List<CheckInRecord>): Int =
        historyNewestFirst.takeWhile { !it.wasTaken }.count()

    /** True once the run of misses reaches the threshold the onboarding screen promises. */
    fun isSustainedSilence(consecutiveMisses: Int): Boolean = consecutiveMisses >= SUSTAINED_MISSES

    /** Onboarding tells the user "after 3 check-ins"; this is that number. */
    const val SUSTAINED_MISSES = 3
}

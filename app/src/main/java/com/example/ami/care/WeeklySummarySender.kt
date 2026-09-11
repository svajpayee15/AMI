package com.example.ami.care

import android.content.Context
import android.util.Log
import com.example.ami.data.AmiPreferences
import com.example.ami.data.MedicineRepository
import com.example.ami.escalation.EscalationClient
import com.example.ami.health.VitalsSummaryLine
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Builds last week's adherence summary and mails it to the whole caregiver chain.
 *
 * Unlike an escalation, this goes to *everyone* configured: a weekly digest is the
 * low-stakes message that keeps the wider family in the loop without the primary
 * contact having to forward anything, and it is the one email nobody needs to act on.
 */
object WeeklySummarySender {

    private const val TAG = "AMI_SUMMARY"

    /**
     * @param force skip the "has a week passed" guard, for the "send now" button
     * @return whether an email was actually accepted by the backend
     */
    suspend fun sendIfDue(context: Context, force: Boolean = false): Boolean {
        val preferences = AmiPreferences(context.applicationContext)

        if (!force && !preferences.isWeeklySummaryEnabledOnce()) {
            Log.i(TAG, "Weekly summary is switched off")
            return false
        }

        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        if (!force && !WeeklySummary.isDue(preferences.getLastSummarySentAt(), now, zone)) {
            Log.i(TAG, "A summary has already gone out for this week")
            return false
        }

        val recipients = preferences.getCaregiversOnce()
        if (recipients.isEmpty()) {
            Log.i(TAG, "No caregivers configured, nothing to send")
            return false
        }

        val report = buildReport(context, LocalDate.now(zone), zone)
        val vitalsLine = VitalsSummaryLine.forEmail(context)

        val sent = EscalationClient().sendSummary(
            caregiverEmails = recipients.map { it.email },
            subject = WeeklySummary.subject(report, PERSON_LABEL),
            body = WeeklySummary.body(report, PERSON_LABEL, vitalsLine)
        )

        // Only a delivered summary updates the marker, so a backend outage means the
        // next run retries rather than skipping the week silently.
        if (sent) preferences.setLastSummarySentAt(now.toEpochMilli())
        Log.i(TAG, "Weekly summary to ${recipients.size} recipient(s) sent=$sent")
        return sent
    }

    /** Exposed so the settings screen can preview exactly what would be emailed. */
    suspend fun buildReport(
        context: Context,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): AdherenceReport {
        val (from, to) = WeeklySummary.windowFor(today)
        val records = MedicineRepository.getInstance(context).checkInsBetween(
            fromEpochMilli = from.atStartOfDay(zone).toInstant().toEpochMilli(),
            toEpochMilli = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        )
        return AdherenceReport.of(records, from, to, zone)
    }

    /** The app supports one recipient of care per device, so this is not configurable yet. */
    private const val PERSON_LABEL = "your relative"

    /** Sunday evening, once the week being reported on has actually finished. */
    val SEND_TIME: LocalTime = LocalTime.of(18, 0)
}

package com.example.ami.care

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Composes the weekly caregiver email from an [AdherenceReport].
 *
 * Written as plain prose rather than a table of counts: the reader is a relative
 * checking their phone, not an analyst. The first line always says the one thing they
 * need to know, because that is often all that gets read.
 *
 * Pure text assembly, so the wording - including the case where nothing was scheduled
 * at all - can be tested without sending mail.
 */
object WeeklySummary {

    private val DATE = DateTimeFormatter.ofPattern("d MMM", Locale.US)

    /**
     * The most recently completed Monday-Sunday week, so the mail only ever reports on
     * a week that has actually finished. On a Sunday that is the week ending today;
     * DayOfWeek.value is 1 for Monday through 7 for Sunday, so `value % 7` is exactly
     * the number of days back to the closing Sunday.
     */
    fun windowFor(today: LocalDate): Pair<LocalDate, LocalDate> {
        val end = today.minusDays((today.dayOfWeek.value % 7).toLong())
        return end.minusDays(6) to end
    }

    fun subject(report: AdherenceReport, personLabel: String): String {
        val percent = report.adherencePercent
        val window = "${report.from.format(DATE)}–${report.to.format(DATE)}"
        return when {
            !report.hasAnyActivity -> "AMI weekly summary for $personLabel — no check-ins ($window)"
            percent != null && percent >= 90 -> "AMI weekly summary for $personLabel — $percent% on track ($window)"
            percent != null -> "AMI weekly summary for $personLabel — $percent% of doses confirmed ($window)"
            else -> "AMI weekly summary for $personLabel ($window)"
        }
    }

    fun body(
        report: AdherenceReport,
        personLabel: String,
        vitalsLine: String? = null
    ): String = buildString {
        appendLine(headline(report, personLabel))
        appendLine()

        if (report.hasAnyActivity) {
            appendLine(
                "Between ${report.from.format(DATE)} and ${report.to.format(DATE)}, AMI ran " +
                    "${report.total} medicine check-${plural(report.total, "in", "ins")}."
            )
            appendLine("  Confirmed taken: ${report.taken}")
            if (report.notTaken > 0) appendLine("  Answered, not yet taken: ${report.notTaken}")
            if (report.noAnswer > 0) appendLine("  No answer: ${report.noAnswer}")
            if (report.declined > 0) appendLine("  Call declined: ${report.declined}")
            appendLine()

            if (report.perMedicine.size > 1) {
                appendLine("By medicine:")
                report.perMedicine.forEach { medicine ->
                    val percent = medicine.adherencePercent
                    appendLine(
                        "  ${medicine.medicineName}: ${medicine.taken} of ${medicine.total} confirmed" +
                            (percent?.let { " ($it%)" } ?: "")
                    )
                }
                appendLine()
            }

            if (report.wellbeingNotes.isNotEmpty()) {
                appendLine("What they said when asked how they were feeling:")
                report.wellbeingNotes.take(MAX_NOTES).forEach { note ->
                    appendLine("  ${note.date.format(DATE)} — \"${note.note}\"")
                }
                appendLine()
            }
        } else {
            appendLine(
                "No medicine check-ins were recorded this week. That usually means no " +
                    "reminders are set up yet, or the phone was switched off at the " +
                    "scheduled times."
            )
            appendLine()
        }

        vitalsLine?.takeIf { it.isNotBlank() }?.let {
            appendLine("Latest readings: $it")
            appendLine()
        }

        appendLine(FOOTER)
    }

    private fun headline(report: AdherenceReport, personLabel: String): String {
        val percent = report.adherencePercent
        val streak = report.currentMissedDayStreak
        return when {
            !report.hasAnyActivity -> "AMI has nothing to report for $personLabel this week."
            streak >= 2 -> "$personLabel has missed every check-in for the last $streak days."
            percent == null -> "AMI checked in with $personLabel this week."
            percent >= 90 -> "$personLabel took $percent% of their scheduled doses. Nothing needs your attention."
            percent >= 70 -> "$personLabel confirmed $percent% of their scheduled doses. A few were missed."
            else -> "$personLabel confirmed only $percent% of their scheduled doses this week."
        }
    }

    private fun plural(count: Int, one: String, many: String) = if (count == 1) one else many

    /**
     * True once a full week has passed since the last summary.
     *
     * Checked rather than trusted to the alarm alone: an alarm that fires while the
     * phone is in Doze can be delivered late or coalesced, and a caregiver receiving
     * two summaries an hour apart learns to ignore them.
     */
    fun isDue(lastSentEpochMilli: Long, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (lastSentEpochMilli <= 0L) return true
        val lastDay = Instant.ofEpochMilli(lastSentEpochMilli).atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return !lastDay.plusDays(MIN_DAYS_BETWEEN).isAfter(today)
    }

    private const val MIN_DAYS_BETWEEN = 6L
    private const val MAX_NOTES = 7

    private const val FOOTER =
        "This summary comes from AMI on the phone itself. It is not medical advice, " +
            "and a confirmed dose means only that it was confirmed out loud.\n" +
            "To stop these emails, turn off the weekly summary in AMI's settings."
}

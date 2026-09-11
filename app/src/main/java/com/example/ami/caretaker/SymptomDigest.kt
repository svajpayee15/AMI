package com.example.ami.caretaker

import com.example.ami.data.SymptomReport
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turns a pile of individual complaints into the shape a caretaker report needs.
 *
 * Plain arithmetic, deliberately. Everything the caretaker agent gates on - whether a
 * red flag was mentioned, whether something keeps coming back - is decided here, before
 * any model is consulted, so that those decisions cannot be hallucinated away. The model
 * is handed the finished counts and writes prose about them; it never gets to decide what
 * counts as recurring.
 *
 * Counting is done two ways at once because they answer different questions. [Entry.mentions]
 * is how many times it came up, which four calls in one bad afternoon will inflate.
 * [Entry.daysAffected] is how many separate days it came up on, which is what actually
 * distinguishes a passing ache from a pattern - and so it is what [Entry.isRecurring]
 * uses.
 */
object SymptomDigest {

    /** A complaint mentioned on this many separate days is treated as a pattern. */
    const val RECURRING_DAYS = 3

    const val DEFAULT_WINDOW_DAYS = 7

    data class Entry(
        val symptom: Symptom,
        /** Total times it came up in the window. */
        val mentions: Int,
        /** Separate calendar days it came up on. */
        val daysAffected: Int,
        val worstSeverity: SymptomSeverity,
        val lastMentionedEpochMilli: Long,
        /** Their own words, from the most recent mention. */
        val lastWords: String
    ) {
        val isRecurring: Boolean get() = daysAffected >= RECURRING_DAYS

        val isRedFlag: Boolean get() = symptom.redFlag

        /** Compact line handed to the model, and readable enough to show as-is. */
        fun describe(): String = buildString {
            append(symptom.label.lowercase())
            append(": $mentions ")
            append(if (mentions == 1) "mention" else "mentions")
            append(" on $daysAffected ")
            append(if (daysAffected == 1) "day" else "days")
            if (worstSeverity != SymptomSeverity.UNKNOWN) {
                append(", worst ${worstSeverity.label}")
            }
            if (lastWords.isNotBlank()) append(", they said \"$lastWords\"")
        }
    }

    data class Digest(
        val windowDays: Int,
        /** Red flags first, then whatever is most persistent. */
        val entries: List<Entry>
    ) {
        val isEmpty: Boolean get() = entries.isEmpty()

        val redFlags: List<Entry> get() = entries.filter { it.isRedFlag }

        val hasRedFlag: Boolean get() = redFlags.isNotEmpty()

        val recurring: List<Entry> get() = entries.filter { it.isRecurring && !it.isRedFlag }

        /** The everyday complaints, which are the only ones ever advised on. */
        val advisable: List<Entry> get() = entries.filter { !it.isRedFlag }

        fun describe(): String =
            if (isEmpty) "no symptoms reported in the last $windowDays days"
            else entries.joinToString("; ") { it.describe() }
    }

    /**
     * @param windowDays counted inclusively of today, so 7 means today plus the six
     *   previous calendar days - not a rolling 168 hours, which would drop this morning's
     *   headache halfway through next Tuesday depending on what time the report was opened.
     */
    fun of(
        reports: List<SymptomReport>,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
        nowEpochMilli: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Digest {
        if (windowDays <= 0) return Digest(windowDays, emptyList())

        val today = dayOf(nowEpochMilli, zone)
        val earliest = today.minusDays((windowDays - 1).toLong())

        val inWindow = reports.filter { report ->
            report.symptom != null && !dayOf(report.timestampEpochMilli, zone).isBefore(earliest)
        }

        val entries = inWindow
            .groupBy { it.symptom!! }
            .map { (symptom, rows) ->
                val newest = rows.maxByOrNull { it.timestampEpochMilli }
                Entry(
                    symptom = symptom,
                    mentions = rows.size,
                    daysAffected = rows.map { dayOf(it.timestampEpochMilli, zone) }.distinct().size,
                    worstSeverity = rows.maxByOrNull { it.severityLevel.ordinal }
                        ?.severityLevel ?: SymptomSeverity.UNKNOWN,
                    lastMentionedEpochMilli = newest?.timestampEpochMilli ?: 0L,
                    lastWords = newest?.rawText.orEmpty()
                )
            }
            .sortedWith(
                // Red flags always surface first; after that, the thing happening on the
                // most days outranks the thing merely mentioned the most times.
                compareByDescending<Entry> { it.isRedFlag }
                    .thenByDescending { it.daysAffected }
                    .thenByDescending { it.mentions }
                    .thenByDescending { it.worstSeverity.ordinal }
                    .thenByDescending { it.lastMentionedEpochMilli }
            )

        return Digest(windowDays, entries)
    }

    private fun dayOf(epochMilli: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMilli).atZone(zone).toLocalDate()
}

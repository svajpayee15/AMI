package com.example.ami.wellbeing

import com.example.ami.data.WellbeingEntry
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * How far back the trend chart looks, and how it groups what it finds.
 *
 * Each range answers "how many buckets, and what is each one called". A week is one
 * bucket per day; longer ranges average days together, because 365 points on a phone
 * chart is a smear, not information.
 */
enum class TrendRange(val bucketCount: Int, val daysPerBucket: Int) {
    WEEK(7, 1),
    MONTH(5, 7),
    YEAR(12, 30);

    /** Total days of history this range covers. */
    val dayCount: Int get() = bucketCount * daysPerBucket
}

/**
 * One plotted point. [value] is null when no reading exists for that bucket, which the
 * chart draws as a gap rather than as zero - an unanswered day is not a calm day.
 */
data class TrendPoint(val label: String, val value: Int?)

/**
 * A dimension's history over one [TrendRange], bucketed and labelled, ready to draw.
 *
 * Pure arithmetic over entries with no Android or database dependency, so the shape of
 * the line can be tested directly rather than eyeballed on a device.
 */
data class TrendSeries(
    val dimension: WellbeingDimension,
    val range: TrendRange,
    val points: List<TrendPoint>
) {
    /** Points that actually carry a reading. */
    val present: List<Int> get() = points.mapNotNull { it.value }

    val hasData: Boolean get() = present.isNotEmpty()

    val average: Int? get() = if (present.isEmpty()) null else present.sum() / present.size

    /**
     * Buckets that have crossed to the concerning side of the "seek help" line.
     *
     * Which side that is depends on the dimension - above the line for stress, below it
     * for connection - so the comparison is delegated rather than written out here.
     */
    val bucketsNeedingAttention: Int
        get() = present.count { dimension.needsAttention(it) }

    companion object {

        /**
         * Builds the series ending on [today] (inclusive).
         *
         * Buckets are filled back to front so the newest day always lands in the last
         * bucket - anchoring at the start instead would slide every point sideways as
         * the week advances, and the chart would appear to animate on its own.
         */
        fun of(
            entries: List<WellbeingEntry>,
            dimension: WellbeingDimension,
            range: TrendRange,
            today: LocalDate,
            locale: Locale = Locale.getDefault()
        ): TrendSeries {
            val byDay = entries.associateBy { it.epochDay }
            val points = (0 until range.bucketCount).map { bucket ->
                // Bucket 0 is the oldest; the last bucket ends on today.
                val bucketsFromEnd = range.bucketCount - 1 - bucket
                val lastDay = today.minusDays((bucketsFromEnd.toLong() * range.daysPerBucket))
                val firstDay = lastDay.minusDays((range.daysPerBucket - 1).toLong())

                val readings = (0 until range.daysPerBucket).mapNotNull { offset ->
                    byDay[firstDay.plusDays(offset.toLong()).toEpochDay()]
                        ?.let { dimension.valueOf(it) }
                }

                TrendPoint(
                    label = range.labelFor(lastDay, locale),
                    value = if (readings.isEmpty()) null else readings.sum() / readings.size
                )
            }
            return TrendSeries(dimension, range, points)
        }

        private fun TrendRange.labelFor(day: LocalDate, locale: Locale): String = when (this) {
            TrendRange.WEEK -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            TrendRange.MONTH -> day.dayOfMonth.toString()
            TrendRange.YEAR -> day.month.getDisplayName(TextStyle.SHORT, locale)
        }
    }
}

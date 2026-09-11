package com.example.ami.wellbeing

import com.example.ami.data.WellbeingEntry
import java.time.DayOfWeek
import java.time.LocalDate

/** Which way sustained stress is heading. [UNKNOWN] when there is not enough history to say. */
enum class BurnoutState { EASING, STABLE, RISING, UNKNOWN }

/**
 * A pattern the data actually supports, for the "What's working for you" cards.
 *
 * Each one carries the numbers that justify it rather than a finished sentence, so the
 * wording stays in string resources and stays translatable. A kind that cannot be
 * supported by the available history is simply not produced - the screen shows fewer
 * cards instead of a confident claim about four days of data.
 */
data class WellbeingInsight(
    val kind: Kind,
    /** The favourable side of the comparison, e.g. average stress after a good night. */
    val betterValue: Int,
    /** The unfavourable side, for the same measure. */
    val worseValue: Int,
    /** Only set for [Kind.CALM_PATTERN]. */
    val bestDay: DayOfWeek? = null
) {
    enum class Kind { CALM_PATTERN, SLEEP_INFLUENCE, CONNECTION_LIFT }

    /** Percentage points between the two sides. Always positive; construction guarantees it. */
    val gap: Int get() = betterValue - worseValue
}

/**
 * Everything the wellbeing screen states as fact, derived in one pass over the history.
 *
 * Nothing here is stored. Every figure is recomputed from [WellbeingEntry] rows on each
 * load, so a correction to a day's reading moves the headline numbers with it and the
 * two can never drift apart.
 *
 * Windows follow what the screen actually claims: the stress and connection cards
 * compare this week against last week, the overall wellbeing card compares this month
 * against last month. Each caption on screen names its own window for that reason.
 */
data class WellbeingStory(
    val stress: MetricChange,
    val burnout: BurnoutState,
    /** Consecutive weeks [burnout] has held. 0 when the state is [BurnoutState.UNKNOWN]. */
    val burnoutWeeks: Int,
    val connections: Int,
    val connectionsChange: MetricChange,
    val wellbeing: MetricChange,
    val insights: List<WellbeingInsight>
) {
    val hasAnyData: Boolean
        get() = stress.current != null || wellbeing.current != null || connections > 0

    companion object {
        private const val WEEK = 7
        private const val MONTH = 30

        /** A pattern claim needs at least this many days on each side to be worth making. */
        private const val MIN_DAYS_PER_SIDE = 3

        /** Sleep at or above this counts as a good night when testing next-day stress. */
        private const val GOOD_SLEEP = 60

        fun of(entries: List<WellbeingEntry>, today: LocalDate): WellbeingStory {
            val byDay = entries.associateBy { it.epochDay }

            fun window(endingDaysAgo: Int, length: Int): List<WellbeingEntry> =
                (0 until length).mapNotNull { offset ->
                    byDay[today.minusDays((endingDaysAgo + offset).toLong()).toEpochDay()]
                }

            val thisWeek = window(0, WEEK)
            val lastWeek = window(WEEK, WEEK)
            val thisMonth = window(0, MONTH)
            val lastMonth = window(MONTH, MONTH)

            return WellbeingStory(
                stress = MetricChange.between(
                    thisWeek.map { it.stress },
                    lastWeek.map { it.stress },
                    higherIsBetter = false
                ),
                burnout = burnoutState(thisWeek, lastWeek),
                burnoutWeeks = burnoutWeeks(byDay, today),
                connections = thisWeek.sumOf { it.connections },
                connectionsChange = MetricChange(
                    current = thisWeek.sumOf { it.connections }.takeIf { thisWeek.isNotEmpty() },
                    previous = lastWeek.sumOf { it.connections }.takeIf { lastWeek.isNotEmpty() },
                    higherIsBetter = true
                ),
                wellbeing = MetricChange.between(
                    thisMonth.map { it.compositeScore() },
                    lastMonth.map { it.compositeScore() },
                    higherIsBetter = true
                ),
                insights = insights(entries)
            )
        }

        /**
         * Overall wellbeing is the mean of the three dimensions on a higher-is-better
         * footing. An unweighted mean is a choice, not a default: weighting one
         * dimension above the others is a clinical claim this app has no basis to make.
         */
        private fun WellbeingEntry.compositeScore(): Int =
            (positiveStress + sleep + social) / 3

        private fun burnoutState(
            thisWeek: List<WellbeingEntry>,
            lastWeek: List<WellbeingEntry>
        ): BurnoutState {
            if (thisWeek.isEmpty() || lastWeek.isEmpty()) return BurnoutState.UNKNOWN
            // The same line the stress chart draws, asked the same way, so the headline
            // and the plot can never disagree about what counts as a hard day.
            val now = thisWeek.count { WellbeingDimension.STRESS.needsAttention(it.stress) }
            val before = lastWeek.count { WellbeingDimension.STRESS.needsAttention(it.stress) }
            return when {
                now > before -> BurnoutState.RISING
                now < before -> BurnoutState.EASING
                else -> BurnoutState.STABLE
            }
        }

        /**
         * How many consecutive weeks back the current state has held, counting the
         * present week as one. Stops at the first week with no readings - a gap is not
         * evidence of continuity.
         */
        private fun burnoutWeeks(byDay: Map<Long, WellbeingEntry>, today: LocalDate): Int {
            fun weekAt(index: Int): List<WellbeingEntry> =
                (0 until WEEK).mapNotNull { offset ->
                    byDay[today.minusDays((index.toLong() * WEEK) + offset).toEpochDay()]
                }

            val current = burnoutState(weekAt(0), weekAt(1))
            if (current == BurnoutState.UNKNOWN) return 0

            var weeks = 1
            var index = 1
            while (true) {
                val newer = weekAt(index)
                val older = weekAt(index + 1)
                if (newer.isEmpty() || older.isEmpty()) break
                if (burnoutState(newer, older) != current) break
                weeks++
                index++
            }
            return weeks
        }

        private fun insights(entries: List<WellbeingEntry>): List<WellbeingInsight> =
            listOfNotNull(
                calmPattern(entries),
                sleepInfluence(entries),
                connectionLift(entries)
            )

        /**
         * The weekday that reliably carries the least stress. Needs the day to have been
         * recorded at least twice, so one unusually good Tuesday cannot become a pattern.
         */
        private fun calmPattern(entries: List<WellbeingEntry>): WellbeingInsight? {
            val byWeekday = entries.groupBy { LocalDate.ofEpochDay(it.epochDay).dayOfWeek }
                .filterValues { it.size >= 2 }
                .mapValues { (_, days) -> days.sumOf { it.stress } / days.size }
            if (byWeekday.size < 2) return null

            val calmest = byWeekday.minByOrNull { it.value } ?: return null
            val rest = byWeekday.filterKeys { it != calmest.key }.values
            val restAverage = rest.sum() / rest.size
            if (restAverage - calmest.value < MEANINGFUL_GAP) return null

            return WellbeingInsight(
                kind = WellbeingInsight.Kind.CALM_PATTERN,
                // Reported as stress avoided, so a bigger number is better news.
                betterValue = restAverage,
                worseValue = calmest.value,
                bestDay = calmest.key
            )
        }

        /**
         * Next-day stress after a good night against next-day stress after a poor one.
         *
         * Deliberately looks at the *following* day: sleeping badly and being stressed on
         * the same day says nothing about which way it runs.
         */
        private fun sleepInfluence(entries: List<WellbeingEntry>): WellbeingInsight? {
            val byDay = entries.associateBy { it.epochDay }
            val afterGood = mutableListOf<Int>()
            val afterPoor = mutableListOf<Int>()

            entries.forEach { night ->
                val nextDay = byDay[night.epochDay + 1] ?: return@forEach
                if (night.sleep >= GOOD_SLEEP) afterGood += nextDay.stress else afterPoor += nextDay.stress
            }
            if (afterGood.size < MIN_DAYS_PER_SIDE || afterPoor.size < MIN_DAYS_PER_SIDE) return null

            val goodAverage = afterGood.sum() / afterGood.size
            val poorAverage = afterPoor.sum() / afterPoor.size
            if (poorAverage - goodAverage < MEANINGFUL_GAP) return null

            return WellbeingInsight(
                kind = WellbeingInsight.Kind.SLEEP_INFLUENCE,
                betterValue = poorAverage,
                worseValue = goodAverage
            )
        }

        /** Social score on days with company against days without. */
        private fun connectionLift(entries: List<WellbeingEntry>): WellbeingInsight? {
            val withCompany = entries.filter { it.connections > 0 }
            val alone = entries.filter { it.connections == 0 }
            if (withCompany.size < MIN_DAYS_PER_SIDE || alone.size < MIN_DAYS_PER_SIDE) return null

            val together = withCompany.sumOf { it.social } / withCompany.size
            val solitary = alone.sumOf { it.social } / alone.size
            if (together - solitary < MEANINGFUL_GAP) return null

            return WellbeingInsight(
                kind = WellbeingInsight.Kind.CONNECTION_LIFT,
                betterValue = together,
                worseValue = solitary
            )
        }

        /**
         * Smallest difference worth putting on screen as a finding. Below this the app
         * would be narrating the noise in its own self-reported data back at the user.
         */
        private const val MEANINGFUL_GAP = 8
    }
}

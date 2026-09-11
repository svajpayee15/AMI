package com.example.ami.wellbeing

import com.example.ami.R
import com.example.ami.data.WellbeingEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class TrendSeriesTest {

    private val locale = Locale.UK

    /** A Sunday, so a WEEK series runs Monday to Sunday. */
    private val today = LocalDate.of(2026, 9, 13)

    private fun entry(day: LocalDate, stress: Int = 40, sleep: Int = 60, social: Int = 50) =
        WellbeingEntry(day.toEpochDay(), stress = stress, sleep = sleep, social = social)

    @Test
    fun `a week produces one bucket per day ending on today`() {
        val entries = (0..6).map { entry(today.minusDays(it.toLong()), stress = 30 + it) }

        val series = TrendSeries.of(entries, WellbeingDimension.STRESS, TrendRange.WEEK, today, locale)

        assertEquals(7, series.points.size)
        // Oldest bucket first: today-6 had stress 36, today had 30.
        assertEquals(36, series.points.first().value)
        assertEquals(30, series.points.last().value)
    }

    @Test
    fun `the newest day always lands in the last bucket`() {
        val series = TrendSeries.of(
            listOf(entry(today, stress = 99)),
            WellbeingDimension.STRESS, TrendRange.WEEK, today, locale
        )

        assertEquals(99, series.points.last().value)
        assertTrue(series.points.dropLast(1).all { it.value == null })
    }

    @Test
    fun `a day with no reading is a gap, not a zero`() {
        val entries = listOf(entry(today), entry(today.minusDays(2)))

        val series = TrendSeries.of(entries, WellbeingDimension.STRESS, TrendRange.WEEK, today, locale)

        assertNull(series.points[5].value)
        assertEquals(2, series.present.size)
    }

    @Test
    fun `a month averages each seven-day bucket`() {
        // Five buckets of 7 days. Fill the most recent bucket with a known spread.
        val entries = (0..6).map { entry(today.minusDays(it.toLong()), stress = 20 + it * 2) }

        val series = TrendSeries.of(entries, WellbeingDimension.STRESS, TrendRange.MONTH, today, locale)

        assertEquals(5, series.points.size)
        // 20,22,24,26,28,30,32 -> 182/7 = 26
        assertEquals(26, series.points.last().value)
        assertTrue(series.points.dropLast(1).all { it.value == null })
    }

    @Test
    fun `a year produces twelve buckets`() {
        val series = TrendSeries.of(
            listOf(entry(today)), WellbeingDimension.STRESS, TrendRange.YEAR, today, locale
        )

        assertEquals(12, series.points.size)
        assertEquals(TrendRange.YEAR.dayCount, 360)
    }

    @Test
    fun `the dimension decides which field is plotted`() {
        val entries = listOf(entry(today, stress = 10, sleep = 80, social = 45))

        fun lastOf(dimension: WellbeingDimension) =
            TrendSeries.of(entries, dimension, TrendRange.WEEK, today, locale).points.last().value

        assertEquals(10, lastOf(WellbeingDimension.STRESS))
        assertEquals(80, lastOf(WellbeingDimension.SLEEP))
        assertEquals(45, lastOf(WellbeingDimension.SOCIAL))
    }

    @Test
    fun `stress needs attention above its line`() {
        val over = WellbeingDimension.STRESS.seekHelpAt!! + 5
        val entries = (0..2).map { entry(today.minusDays(it.toLong()), stress = over) }

        val series = TrendSeries.of(entries, WellbeingDimension.STRESS, TrendRange.WEEK, today, locale)

        assertEquals(3, series.bucketsNeedingAttention)
    }

    @Test
    fun `connection needs attention BELOW its line, not above`() {
        // The inversion that matters: a high social score is good, so the red rule on
        // that plot is a floor. Treating it like the stress ceiling would flag exactly
        // the weeks that are going well.
        val floor = WellbeingDimension.SOCIAL.seekHelpAt!!
        val lonely = (0..2).map { entry(today.minusDays(it.toLong()), social = floor - 5) }
        val sociable = (0..2).map { entry(today.minusDays(it.toLong()), social = floor + 40) }

        val low = TrendSeries.of(lonely, WellbeingDimension.SOCIAL, TrendRange.WEEK, today, locale)
        val high = TrendSeries.of(sociable, WellbeingDimension.SOCIAL, TrendRange.WEEK, today, locale)

        assertEquals(3, low.bucketsNeedingAttention)
        assertEquals(0, high.bucketsNeedingAttention)
    }

    @Test
    fun `sleep has no line at all, so nothing ever needs attention`() {
        val entries = (0..6).map { entry(today.minusDays(it.toLong()), sleep = 2) }

        val series = TrendSeries.of(entries, WellbeingDimension.SLEEP, TrendRange.WEEK, today, locale)

        assertNull(WellbeingDimension.SLEEP.seekHelpAt)
        assertFalse(WellbeingDimension.SLEEP.hasSeekHelpThreshold)
        assertEquals(0, series.bucketsNeedingAttention)
    }

    @Test
    fun `a reading exactly on the line counts on both kinds of threshold`() {
        val stressAt = WellbeingDimension.STRESS.seekHelpAt!!
        val socialAt = WellbeingDimension.SOCIAL.seekHelpAt!!

        val stress = TrendSeries.of(
            listOf(entry(today, stress = stressAt)),
            WellbeingDimension.STRESS, TrendRange.WEEK, today, locale
        )
        val social = TrendSeries.of(
            listOf(entry(today, social = socialAt)),
            WellbeingDimension.SOCIAL, TrendRange.WEEK, today, locale
        )

        assertEquals(1, stress.bucketsNeedingAttention)
        assertEquals(1, social.bucketsNeedingAttention)
    }

    @Test
    fun `the axis ends swap with the direction of the scale`() {
        // Stress is the only dimension whose chart has "bad" at the top.
        assertEquals(R.string.wellbeing_axis_bad, WellbeingDimension.STRESS.axisTopLabelRes)
        assertEquals(R.string.wellbeing_axis_normal, WellbeingDimension.STRESS.axisBottomLabelRes)

        listOf(WellbeingDimension.SLEEP, WellbeingDimension.SOCIAL).forEach { dimension ->
            assertEquals(R.string.wellbeing_axis_normal, dimension.axisTopLabelRes)
            assertEquals(R.string.wellbeing_axis_bad, dimension.axisBottomLabelRes)
        }
    }

    @Test
    fun `each dimension plots in its own colour`() {
        val colours = WellbeingDimension.entries.map { it.lineColorRes }

        assertEquals(colours.size, colours.toSet().size)
    }

    @Test
    fun `no entries means no data and no average`() {
        val series = TrendSeries.of(emptyList(), WellbeingDimension.STRESS, TrendRange.WEEK, today, locale)

        assertFalse(series.hasData)
        assertNull(series.average)
        assertEquals(7, series.points.size)
    }

    @Test
    fun `entries outside the range are ignored`() {
        val series = TrendSeries.of(
            listOf(entry(today.minusDays(400), stress = 90), entry(today, stress = 10)),
            WellbeingDimension.STRESS, TrendRange.WEEK, today, locale
        )

        assertEquals(1, series.present.size)
        assertEquals(10, series.average)
    }

    @Test
    fun `week buckets are labelled with weekday names`() {
        val series = TrendSeries.of(
            listOf(entry(today)), WellbeingDimension.STRESS, TrendRange.WEEK, today, locale
        )

        assertEquals("Sun", series.points.last().label)
        assertEquals("Mon", series.points.first().label)
    }
}

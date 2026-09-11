package com.example.ami.wellbeing

import com.example.ami.data.WellbeingEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class WellbeingStoryTest {

    /** A Sunday. Weekday-sensitive assertions below depend on this. */
    private val today = LocalDate.of(2026, 9, 13)

    private fun entry(
        daysAgo: Int,
        stress: Int = 40,
        sleep: Int = 60,
        social: Int = 50,
        connections: Int = 0
    ) = WellbeingEntry(
        epochDay = today.minusDays(daysAgo.toLong()).toEpochDay(),
        stress = stress,
        sleep = sleep,
        social = social,
        connections = connections
    )

    private val calm = 30
    private val high = WellbeingDimension.STRESS.seekHelpAt!! + 10

    // ------------------------------------------------------------------ headline

    @Test
    fun `an empty history reports no data and claims nothing`() {
        val story = WellbeingStory.of(emptyList(), today)

        assertFalse(story.hasAnyData)
        assertNull(story.stress.current)
        assertNull(story.wellbeing.current)
        assertEquals(BurnoutState.UNKNOWN, story.burnout)
        assertEquals(0, story.burnoutWeeks)
        assertTrue(story.insights.isEmpty())
    }

    @Test
    fun `stress compares this week against last week and calls a fall an improvement`() {
        val thisWeek = (0..6).map { entry(it, stress = 30) }
        val lastWeek = (7..13).map { entry(it, stress = 50) }

        val story = WellbeingStory.of(thisWeek + lastWeek, today)

        assertEquals(30, story.stress.current)
        assertEquals(50, story.stress.previous)
        assertEquals(20, story.stress.magnitude)
        assertEquals(MetricDirection.DOWN, story.stress.direction)
        assertEquals(true, story.stress.isImprovement)
    }

    @Test
    fun `overall wellbeing compares this month against last month`() {
        // Composite = (100 - stress + sleep + social) / 3.
        val thisMonth = (0..29).map { entry(it, stress = 20, sleep = 80, social = 80) }
        val lastMonth = (30..59).map { entry(it, stress = 60, sleep = 40, social = 40) }

        val story = WellbeingStory.of(thisMonth + lastMonth, today)

        assertEquals((80 + 80 + 80) / 3, story.wellbeing.current)
        assertEquals((40 + 40 + 40) / 3, story.wellbeing.previous)
        assertEquals(MetricDirection.UP, story.wellbeing.direction)
        assertEquals(true, story.wellbeing.isImprovement)
    }

    @Test
    fun `connections are summed over the week, not averaged`() {
        val entries = (0..6).map { entry(it, connections = 2) }

        val story = WellbeingStory.of(entries, today)

        assertEquals(14, story.connections)
    }

    // ------------------------------------------------------------------ burnout

    @Test
    fun `more high-stress days than last week reads as rising`() {
        val thisWeek = (0..6).map { entry(it, stress = if (it < 4) high else calm) }
        val lastWeek = (7..13).map { entry(it, stress = if (it < 8) high else calm) }

        val story = WellbeingStory.of(thisWeek + lastWeek, today)

        assertEquals(BurnoutState.RISING, story.burnout)
    }

    @Test
    fun `fewer high-stress days than last week reads as easing`() {
        val thisWeek = (0..6).map { entry(it, stress = calm) }
        val lastWeek = (7..13).map { entry(it, stress = high) }

        val story = WellbeingStory.of(thisWeek + lastWeek, today)

        assertEquals(BurnoutState.EASING, story.burnout)
    }

    @Test
    fun `the same count two weeks running reads as stable`() {
        val entries = (0..13).map { entry(it, stress = calm) }

        val story = WellbeingStory.of(entries, today)

        assertEquals(BurnoutState.STABLE, story.burnout)
    }

    @Test
    fun `burnout is unknown without a previous week to compare against`() {
        val story = WellbeingStory.of((0..6).map { entry(it) }, today)

        assertEquals(BurnoutState.UNKNOWN, story.burnout)
        assertEquals(0, story.burnoutWeeks)
    }

    @Test
    fun `a stable run is counted in whole weeks`() {
        // Four weeks of identical readings: stable now, and stable for each comparison back.
        val entries = (0..27).map { entry(it, stress = calm) }

        val story = WellbeingStory.of(entries, today)

        assertEquals(BurnoutState.STABLE, story.burnout)
        assertEquals(3, story.burnoutWeeks)
    }

    @Test
    fun `a gap in history stops the streak rather than bridging it`() {
        // Two weeks present, week three missing entirely.
        val entries = (0..13).map { entry(it, stress = calm) } + (21..27).map { entry(it, stress = calm) }

        val story = WellbeingStory.of(entries, today)

        assertEquals(1, story.burnoutWeeks)
    }

    // ------------------------------------------------------------------ insights

    @Test
    fun `a reliably calm weekday becomes a pattern`() {
        // Sundays much calmer than every other day, over four weeks.
        val entries = (0..27).map {
            val day = today.minusDays(it.toLong())
            entry(it, stress = if (day.dayOfWeek == DayOfWeek.SUNDAY) 20 else 60)
        }

        val story = WellbeingStory.of(entries, today)
        val calmPattern = story.insights.single { it.kind == WellbeingInsight.Kind.CALM_PATTERN }

        assertEquals(DayOfWeek.SUNDAY, calmPattern.bestDay)
        assertEquals(40, calmPattern.gap)
    }

    @Test
    fun `one good day is not a pattern`() {
        // A single calm Tuesday among otherwise identical days: not recorded twice, so
        // it never qualifies.
        val entries = (0..6).map {
            val day = today.minusDays(it.toLong())
            entry(it, stress = if (day.dayOfWeek == DayOfWeek.TUESDAY) 10 else 60)
        }

        val story = WellbeingStory.of(entries, today)

        assertTrue(story.insights.none { it.kind == WellbeingInsight.Kind.CALM_PATTERN })
    }

    @Test
    fun `a difference smaller than the meaningful gap is not reported`() {
        val entries = (0..27).map {
            val day = today.minusDays(it.toLong())
            entry(it, stress = if (day.dayOfWeek == DayOfWeek.SUNDAY) 58 else 60)
        }

        val story = WellbeingStory.of(entries, today)

        assertTrue(story.insights.none { it.kind == WellbeingInsight.Kind.CALM_PATTERN })
    }

    @Test
    fun `sleep influence looks at the day after the night, not the same day`() {
        // Alternating nights. Good sleep is followed by a calm day; poor sleep by a
        // stressed one. Same-day pairing would show the opposite relationship.
        val entries = (0..19).map { daysAgo ->
            val goodNight = daysAgo % 2 == 1
            entry(
                daysAgo,
                sleep = if (goodNight) 80 else 30,
                stress = if (goodNight) 70 else 20
            )
        }

        val story = WellbeingStory.of(entries, today)
        val sleep = story.insights.single { it.kind == WellbeingInsight.Kind.SLEEP_INFLUENCE }

        // Day after a good night carries stress 20; after a poor night, 70.
        assertEquals(70, sleep.betterValue)
        assertEquals(20, sleep.worseValue)
        assertEquals(50, sleep.gap)
    }

    @Test
    fun `sleep influence needs enough nights on both sides`() {
        // Only two poor nights - below the minimum per side.
        val entries = (0..19).map { daysAgo ->
            entry(daysAgo, sleep = if (daysAgo < 2) 30 else 80, stress = 40)
        }

        val story = WellbeingStory.of(entries, today)

        assertTrue(story.insights.none { it.kind == WellbeingInsight.Kind.SLEEP_INFLUENCE })
    }

    @Test
    fun `days with company scoring higher becomes a connection insight`() {
        val entries = (0..19).map { daysAgo ->
            val withCompany = daysAgo % 2 == 0
            entry(
                daysAgo,
                social = if (withCompany) 80 else 40,
                connections = if (withCompany) 2 else 0
            )
        }

        val story = WellbeingStory.of(entries, today)
        val lift = story.insights.single { it.kind == WellbeingInsight.Kind.CONNECTION_LIFT }

        assertEquals(40, lift.gap)
    }

    @Test
    fun `insights are omitted rather than guessed when history is thin`() {
        val story = WellbeingStory.of(listOf(entry(0), entry(1)), today)

        assertTrue(story.insights.isEmpty())
    }
}

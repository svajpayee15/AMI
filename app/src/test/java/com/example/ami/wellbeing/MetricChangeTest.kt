package com.example.ami.wellbeing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricChangeTest {

    @Test
    fun `a rise in a higher-is-better metric is an improvement`() {
        val change = MetricChange(current = 70, previous = 60, higherIsBetter = true)

        assertEquals(MetricDirection.UP, change.direction)
        assertEquals(10, change.magnitude)
        assertEquals(true, change.isImprovement)
    }

    @Test
    fun `a rise in a lower-is-better metric is not an improvement`() {
        val change = MetricChange(current = 70, previous = 60, higherIsBetter = false)

        assertEquals(MetricDirection.UP, change.direction)
        assertEquals(false, change.isImprovement)
    }

    @Test
    fun `stress falling and sleep rising are both improvements despite opposite arrows`() {
        val stress = MetricChange(current = 40, previous = 55, higherIsBetter = false)
        val sleep = MetricChange(current = 72, previous = 58, higherIsBetter = true)

        assertEquals(MetricDirection.DOWN, stress.direction)
        assertEquals(MetricDirection.UP, sleep.direction)
        assertEquals(true, stress.isImprovement)
        assertEquals(true, sleep.isImprovement)
    }

    @Test
    fun `drift smaller than the steady band reads as steady with no verdict`() {
        val change = MetricChange(current = 61, previous = 60, higherIsBetter = true)

        assertEquals(MetricDirection.STEADY, change.direction)
        assertNull(change.isImprovement)
    }

    @Test
    fun `movement exactly at the steady band counts as a direction`() {
        val change = MetricChange(
            current = 60 + MetricChange.STEADY_BAND,
            previous = 60,
            higherIsBetter = true
        )

        assertEquals(MetricDirection.UP, change.direction)
    }

    @Test
    fun `an empty window gives no magnitude rather than a zero change`() {
        val change = MetricChange.between(emptyList(), listOf(50, 60), higherIsBetter = true)

        assertNull(change.current)
        assertNull(change.magnitude)
        assertEquals(MetricDirection.STEADY, change.direction)
        assertNull(change.isImprovement)
    }

    @Test
    fun `averaging ignores absent days instead of counting them as zero`() {
        assertEquals(60, MetricChange.averageOf(listOf(50, null, 70)))
        assertNull(MetricChange.averageOf(listOf(null, null)))
    }

    @Test
    fun `averaging rounds to nearest rather than truncating`() {
        // 50 + 51 + 52 = 153, /3 = 51. 50 + 51 = 101, /2 = 50.5 -> 51.
        assertEquals(51, MetricChange.averageOf(listOf(50, 51, 52)))
        assertEquals(51, MetricChange.averageOf(listOf(50, 51)))
    }

    @Test
    fun `magnitude is never negative - direction carries the sign`() {
        val fell = MetricChange(current = 30, previous = 80, higherIsBetter = true)

        assertEquals(50, fell.magnitude)
        assertEquals(MetricDirection.DOWN, fell.direction)
    }
}

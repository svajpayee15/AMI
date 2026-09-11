package com.example.ami.wellbeing

import kotlin.math.abs
import kotlin.math.roundToInt

/** Which way the arrow points. Purely about the number, not about whether it is good news. */
enum class MetricDirection { UP, DOWN, STEADY }

/**
 * One window's average against the window before it.
 *
 * Separating [direction] (which way the number moved) from [isImprovement] (whether that
 * is good) is deliberate. Stress falling and sleep rising are both progress but point
 * opposite ways, and a screen that colours every downward arrow red would tell someone
 * their stress dropping is a problem.
 */
data class MetricChange(
    val current: Int?,
    val previous: Int?,
    val higherIsBetter: Boolean
) {
    /**
     * Percentage-point movement, always non-negative - [direction] carries the sign.
     * Null when either window is empty, which the UI shows as "not enough yet" rather
     * than as no change.
     */
    val magnitude: Int? = if (current == null || previous == null) null else abs(current - previous)

    val direction: MetricDirection = when {
        current == null || previous == null -> MetricDirection.STEADY
        // A single point of drift on a self-reported 0-100 scale is noise, not a trend.
        abs(current - previous) < STEADY_BAND -> MetricDirection.STEADY
        current > previous -> MetricDirection.UP
        else -> MetricDirection.DOWN
    }

    /**
     * Null when steady or when a window is missing: "is this better?" has no honest
     * answer in either case, and the caller should say nothing rather than guess.
     */
    val isImprovement: Boolean? = when (direction) {
        MetricDirection.STEADY -> null
        MetricDirection.UP -> higherIsBetter
        MetricDirection.DOWN -> !higherIsBetter
    }

    companion object {
        /** Movement smaller than this reads as STEADY. */
        const val STEADY_BAND = 2

        /** Mean of the readings present, or null if there are none. Never treats absent as zero. */
        fun averageOf(values: List<Int?>): Int? {
            val present = values.filterNotNull()
            return if (present.isEmpty()) null else (present.sum().toDouble() / present.size).roundToInt()
        }

        fun between(
            currentWindow: List<Int?>,
            previousWindow: List<Int?>,
            higherIsBetter: Boolean
        ): MetricChange = MetricChange(
            current = averageOf(currentWindow),
            previous = averageOf(previousWindow),
            higherIsBetter = higherIsBetter
        )
    }
}

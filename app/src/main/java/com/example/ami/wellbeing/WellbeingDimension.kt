package com.example.ami.wellbeing

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.example.ami.R
import com.example.ami.data.WellbeingEntry

/**
 * The three things a person is asked about directly, and the one tab each gets.
 *
 * [higherIsBetter] is the whole reason this enum exists rather than a raw string, and it
 * drives more than the arrows. The same "went up by 4" means relief for sleep and trouble
 * for stress; the plot's ends swap labels with it; and it decides whether the red line is
 * a ceiling to stay under or a floor to stay above. Getting it wrong once would invert
 * the meaning of the whole screen, so it is stated once, here, and never re-derived.
 */
enum class WellbeingDimension(
    @get:StringRes val tabLabelRes: Int,
    @get:StringRes val trendTitleRes: Int,
    @get:StringRes val legendLabelRes: Int,
    @get:ColorRes val lineColorRes: Int,
    val higherIsBetter: Boolean,
    /**
     * Where the red dotted "seek help" line sits, or null for a dimension that has no
     * concerning end. Sleep has none: a bad night is not on its own a reason to act.
     */
    val seekHelpAt: Int?
) {
    STRESS(
        tabLabelRes = R.string.wellbeing_tab_stress,
        trendTitleRes = R.string.wellbeing_trend_stress,
        legendLabelRes = R.string.wellbeing_legend_stress,
        lineColorRes = R.color.ami_chart_line_stress,
        higherIsBetter = false,
        seekHelpAt = 68
    ),
    SLEEP(
        tabLabelRes = R.string.wellbeing_tab_sleep,
        trendTitleRes = R.string.wellbeing_trend_sleep,
        legendLabelRes = R.string.wellbeing_legend_sleep,
        lineColorRes = R.color.ami_chart_line_sleep,
        higherIsBetter = true,
        seekHelpAt = null
    ),
    SOCIAL(
        tabLabelRes = R.string.wellbeing_tab_social,
        trendTitleRes = R.string.wellbeing_trend_social,
        legendLabelRes = R.string.wellbeing_legend_social,
        lineColorRes = R.color.ami_chart_line_social,
        higherIsBetter = true,
        seekHelpAt = 25
    );

    fun valueOf(entry: WellbeingEntry): Int = when (this) {
        STRESS -> entry.stress
        SLEEP -> entry.sleep
        SOCIAL -> entry.social
    }

    val hasSeekHelpThreshold: Boolean get() = seekHelpAt != null

    /**
     * Which side of the line is the worrying one.
     *
     * Falls straight out of [higherIsBetter]: for stress the line is a ceiling to stay
     * under, for connection it is a floor to stay above. The chart draws the same dotted
     * red rule either way - what changes is which side of it counts.
     */
    val seekHelpIsFloor: Boolean get() = higherIsBetter

    /** True when [value] has crossed into the concerning side of the line. */
    fun needsAttention(value: Int): Boolean {
        val threshold = seekHelpAt ?: return false
        return if (seekHelpIsFloor) value <= threshold else value >= threshold
    }

    /**
     * The words at the two ends of the y-axis. They swap with the scale's direction:
     * on the stress plot the top of the chart is the bad end, on sleep and connection
     * it is the good one.
     */
    @get:StringRes
    val axisTopLabelRes: Int
        get() = if (higherIsBetter) R.string.wellbeing_axis_normal else R.string.wellbeing_axis_bad

    @get:StringRes
    val axisBottomLabelRes: Int
        get() = if (higherIsBetter) R.string.wellbeing_axis_bad else R.string.wellbeing_axis_normal
}

package com.example.ami.wellbeing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.ami.R
import com.example.ami.navigation.AmiNavBar
import com.example.ami.navigation.AmiTab
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale

/**
 * The wellbeing story: four headline figures, one trend at a time, and the patterns the
 * recorded history actually supports.
 *
 * Every number on screen is recomputed from stored readings on each load. Nothing is
 * placeholder or sample data - when there are no readings the screen says so, which is
 * the honest thing for a health-adjacent surface to do and the only thing that keeps a
 * caregiver from reading a decorative number as a measurement.
 */
class WellbeingActivity : AppCompatActivity() {

    private lateinit var repository: WellbeingRepository

    private var dimension = WellbeingDimension.STRESS
    private var range = TrendRange.WEEK

    private lateinit var dimensionTabs: Map<WellbeingDimension, TextView>
    private lateinit var rangeTabs: Map<TrendRange, TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keeps the bar visible with Health lit, because that is the tab this screen
        // belongs to - but isTabRoot = false, so tapping Health here walks back up to
        // the vitals screen rather than doing nothing.
        AmiNavBar.setContentView(this, R.layout.activity_wellbeing, AmiTab.HEALTH, isTabRoot = false)
        repository = WellbeingRepository(this)

        dimensionTabs = mapOf(
            WellbeingDimension.STRESS to findViewById(R.id.tabStress),
            WellbeingDimension.SLEEP to findViewById(R.id.tabSleep),
            WellbeingDimension.SOCIAL to findViewById(R.id.tabSocial)
        )
        rangeTabs = mapOf(
            TrendRange.WEEK to findViewById(R.id.rangeWeek),
            TrendRange.MONTH to findViewById(R.id.rangeMonth),
            TrendRange.YEAR to findViewById(R.id.rangeYear)
        )

        dimensionTabs.forEach { (value, tab) ->
            tab.setOnClickListener {
                dimension = value
                refresh()
            }
        }
        rangeTabs.forEach { (value, tab) ->
            tab.setOnClickListener {
                range = value
                refresh()
            }
        }

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }

        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DIMENSION, dimension.name)
        outState.putString(STATE_RANGE, range.name)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        savedInstanceState.getString(STATE_DIMENSION)?.let { dimension = WellbeingDimension.valueOf(it) }
        savedInstanceState.getString(STATE_RANGE)?.let { range = TrendRange.valueOf(it) }
        refresh()
    }

    private fun refresh() {
        paintSegments()
        lifecycleScope.launch {
            val (story, series) = repository.load(dimension, range)
            bindStory(story)
            bindTrend(series)
            bindInsights(story)
        }
    }

    // ---------------------------------------------------------------- segments

    private fun paintSegments() {
        dimensionTabs.forEach { (value, tab) -> paintSegment(tab, value == dimension) }
        rangeTabs.forEach { (value, tab) -> paintSegment(tab, value == range) }

        findViewById<TextView>(R.id.trendTitle).setText(dimension.trendTitleRes)

        // Legend follows the plot: same colour as the line, same words as the tab.
        findViewById<View>(R.id.legendSeriesSwatch)
            .setBackgroundColor(ContextCompat.getColor(this, dimension.lineColorRes))
        findViewById<TextView>(R.id.legendSeriesLabel).setText(dimension.legendLabelRes)

        // Sleep has no "seek help" line, so it gets no key for one.
        val thresholdShown = if (dimension.hasSeekHelpThreshold) View.VISIBLE else View.GONE
        findViewById<View>(R.id.legendThresholdSwatch).visibility = thresholdShown
        findViewById<View>(R.id.legendThresholdLabel).visibility = thresholdShown
    }

    private fun paintSegment(tab: TextView, selected: Boolean) {
        tab.setBackgroundResource(if (selected) R.drawable.bg_segment_selected else 0)
        tab.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.ami_text_primary else R.color.ami_text_secondary
            )
        )
        // Spoken as "selected" by TalkBack rather than signalled only by the pill.
        tab.isSelected = selected
    }

    // ---------------------------------------------------------------- headline grid

    private fun bindStory(story: WellbeingStory) {
        findViewById<View>(R.id.wellbeingEmpty).visibility =
            if (story.hasAnyData) View.GONE else View.VISIBLE
        findViewById<View>(R.id.metricGrid).visibility =
            if (story.hasAnyData) View.VISIBLE else View.GONE
        if (!story.hasAnyData) return

        bindMetric(
            hostId = R.id.metricStress,
            iconRes = R.drawable.ic_wb_calm,
            tileRes = R.drawable.bg_tile_calm,
            tintRes = R.color.ami_on_tile_calm,
            labelRes = R.string.wellbeing_metric_stress,
            value = story.stress.magnitude?.let { getString(R.string.wellbeing_value_percent, it) },
            change = story.stress,
            captionRes = R.string.wellbeing_caption_vs_last_week
        )

        bindMetric(
            hostId = R.id.metricBurnout,
            iconRes = R.drawable.ic_wb_burnout,
            tileRes = R.drawable.bg_tile_focus,
            tintRes = R.color.ami_on_tile_focus,
            labelRes = R.string.wellbeing_metric_burnout,
            value = getString(
                when (story.burnout) {
                    BurnoutState.EASING -> R.string.wellbeing_burnout_easing
                    BurnoutState.STABLE -> R.string.wellbeing_burnout_stable
                    BurnoutState.RISING -> R.string.wellbeing_burnout_rising
                    BurnoutState.UNKNOWN -> R.string.wellbeing_burnout_unknown
                }
            ),
            // Burnout's arrow reports the state itself, not a delta between windows.
            arrowRes = when (story.burnout) {
                BurnoutState.EASING -> R.drawable.ic_trend_down
                BurnoutState.RISING -> R.drawable.ic_trend_up
                else -> R.drawable.ic_trend_steady
            },
            arrowTintRes = when (story.burnout) {
                BurnoutState.EASING -> R.color.ami_success
                BurnoutState.RISING -> R.color.ami_danger
                else -> R.color.ami_text_secondary
            },
            caption = if (story.burnoutWeeks == 0) {
                getString(R.string.wellbeing_caption_not_enough)
            } else {
                resources.getQuantityString(
                    R.plurals.wellbeing_caption_steady_weeks,
                    story.burnoutWeeks,
                    story.burnoutWeeks
                )
            }
        )

        bindMetric(
            hostId = R.id.metricConnection,
            iconRes = R.drawable.ic_wb_connection,
            tileRes = R.drawable.bg_tile_connect,
            tintRes = R.color.ami_on_tile_connect,
            labelRes = R.string.wellbeing_metric_connection,
            value = getString(R.string.wellbeing_value_count, story.connections),
            change = story.connectionsChange,
            captionRes = R.string.wellbeing_caption_people_week
        )

        bindMetric(
            hostId = R.id.metricWellbeing,
            iconRes = R.drawable.ic_wb_thrive,
            tileRes = R.drawable.bg_tile_thrive,
            tintRes = R.color.ami_on_tile_thrive,
            labelRes = R.string.wellbeing_metric_wellbeing,
            value = story.wellbeing.magnitude?.let { getString(R.string.wellbeing_value_percent, it) },
            change = story.wellbeing,
            captionRes = R.string.wellbeing_caption_vs_last_month
        )
    }

    private fun bindMetric(
        hostId: Int,
        @DrawableRes iconRes: Int,
        @DrawableRes tileRes: Int,
        @ColorRes tintRes: Int,
        labelRes: Int,
        value: String?,
        change: MetricChange? = null,
        @DrawableRes arrowRes: Int? = null,
        @ColorRes arrowTintRes: Int? = null,
        captionRes: Int? = null,
        caption: String? = null
    ) {
        val host = findViewById<View>(hostId)

        host.findViewById<ImageView>(R.id.metricIcon).apply {
            setBackgroundResource(tileRes)
            setImageResource(iconRes)
            imageTintList = ContextCompat.getColorStateList(this@WellbeingActivity, tintRes)
        }
        host.findViewById<TextView>(R.id.metricLabel).setText(labelRes)
        host.findViewById<TextView>(R.id.metricValue).text =
            value ?: getString(R.string.wellbeing_value_none)

        val arrow = host.findViewById<ImageView>(R.id.metricArrow)
        val resolvedArrow = arrowRes ?: change?.let { arrowFor(it.direction) }
        if (resolvedArrow == null || value == null) {
            arrow.visibility = View.GONE
        } else {
            arrow.visibility = View.VISIBLE
            arrow.setImageResource(resolvedArrow)
            val tint = arrowTintRes ?: change?.let { tintFor(it) } ?: R.color.ami_text_secondary
            arrow.imageTintList = ContextCompat.getColorStateList(this, tint)
        }

        host.findViewById<TextView>(R.id.metricCaption).text = when {
            caption != null -> caption
            value == null -> getString(R.string.wellbeing_caption_not_enough)
            captionRes != null -> getString(captionRes)
            else -> ""
        }
    }

    private fun arrowFor(direction: MetricDirection): Int = when (direction) {
        MetricDirection.UP -> R.drawable.ic_trend_up
        MetricDirection.DOWN -> R.drawable.ic_trend_down
        MetricDirection.STEADY -> R.drawable.ic_trend_steady
    }

    /**
     * Colour follows [MetricChange.isImprovement], not the arrow direction. Stress
     * falling and sleep rising are both green even though they point opposite ways.
     */
    @ColorRes
    private fun tintFor(change: MetricChange): Int = when (change.isImprovement) {
        true -> R.color.ami_success
        false -> R.color.ami_danger
        null -> R.color.ami_text_secondary
    }

    // ---------------------------------------------------------------- trend

    private fun bindTrend(series: TrendSeries) {
        val rangeLabel = getString(rangeLabelRes(series.range))
        val dimensionLabel = getString(series.dimension.trendTitleRes)

        val description = if (!series.hasData) {
            getString(R.string.wellbeing_chart_description_empty, dimensionLabel, rangeLabel)
        } else {
            val threshold = when {
                !series.dimension.hasSeekHelpThreshold -> ""
                series.bucketsNeedingAttention > 0 -> resources.getQuantityString(
                    R.plurals.wellbeing_chart_needs_attention,
                    series.bucketsNeedingAttention,
                    series.bucketsNeedingAttention
                )
                else -> getString(R.string.wellbeing_chart_no_attention)
            }
            getString(
                R.string.wellbeing_chart_description,
                dimensionLabel,
                rangeLabel,
                series.average ?: 0,
                threshold
            )
        }

        findViewById<TrendChartView>(R.id.trendChart).show(series, description)
    }

    private fun rangeLabelRes(range: TrendRange): Int = when (range) {
        TrendRange.WEEK -> R.string.wellbeing_range_week
        TrendRange.MONTH -> R.string.wellbeing_range_month
        TrendRange.YEAR -> R.string.wellbeing_range_year
    }

    // ---------------------------------------------------------------- insights

    private fun bindInsights(story: WellbeingStory) {
        val list = findViewById<LinearLayout>(R.id.insightList)
        list.removeAllViews()

        // The heading would otherwise sit above nothing on a sparse history.
        findViewById<View>(R.id.workingTitle).visibility =
            if (story.insights.isEmpty()) View.GONE else View.VISIBLE

        val inflater = LayoutInflater.from(this)
        story.insights.forEachIndexed { index, insight ->
            val card = inflater.inflate(R.layout.item_wellbeing_insight, list, false)
            if (index > 0) {
                (card.layoutParams as LinearLayout.LayoutParams).topMargin =
                    resources.getDimensionPixelSize(R.dimen.space_sm)
            }

            val (iconRes, tileRes, tintRes) = when (insight.kind) {
                WellbeingInsight.Kind.CALM_PATTERN ->
                    Triple(R.drawable.ic_wb_calm, R.drawable.bg_tile_calm, R.color.ami_on_tile_calm)
                WellbeingInsight.Kind.SLEEP_INFLUENCE ->
                    Triple(R.drawable.ic_wb_moon, R.drawable.bg_tile_focus, R.color.ami_on_tile_focus)
                WellbeingInsight.Kind.CONNECTION_LIFT ->
                    Triple(R.drawable.ic_wb_connection, R.drawable.bg_tile_connect, R.color.ami_on_tile_connect)
            }

            card.findViewById<ImageView>(R.id.insightIcon).apply {
                setBackgroundResource(tileRes)
                setImageResource(iconRes)
                imageTintList = ContextCompat.getColorStateList(this@WellbeingActivity, tintRes)
            }
            card.findViewById<TextView>(R.id.insightTitle).setText(
                when (insight.kind) {
                    WellbeingInsight.Kind.CALM_PATTERN -> R.string.wellbeing_insight_calm_title
                    WellbeingInsight.Kind.SLEEP_INFLUENCE -> R.string.wellbeing_insight_sleep_title
                    WellbeingInsight.Kind.CONNECTION_LIFT -> R.string.wellbeing_insight_connection_title
                }
            )
            card.findViewById<TextView>(R.id.insightBody).text = when (insight.kind) {
                WellbeingInsight.Kind.CALM_PATTERN -> resources.getQuantityString(
                    R.plurals.wellbeing_insight_calm_body,
                    insight.gap,
                    insight.bestDay?.getDisplayName(TextStyle.FULL, Locale.getDefault()).orEmpty(),
                    insight.gap
                )
                WellbeingInsight.Kind.SLEEP_INFLUENCE -> resources.getQuantityString(
                    R.plurals.wellbeing_insight_sleep_body, insight.gap, insight.gap
                )
                WellbeingInsight.Kind.CONNECTION_LIFT -> resources.getQuantityString(
                    R.plurals.wellbeing_insight_connection_body, insight.gap, insight.gap
                )
            }

            list.addView(card)
        }
    }

    private companion object {
        const val STATE_DIMENSION = "dimension"
        const val STATE_RANGE = "range"
    }
}

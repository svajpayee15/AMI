package com.example.ami.wellbeing

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.example.ami.R
import com.example.ami.data.WellbeingEntry

/**
 * The trend line, drawn by hand.
 *
 * There is no charting library in this project's dependencies and one line does not
 * justify adding one - a chart library would arrive with its own type sizes, its own
 * colours and its own touch targets, all of which this app has deliberate opinions
 * about.
 *
 * Gaps are gaps: a bucket with no reading breaks the line rather than being drawn at
 * zero or bridged to the next point, because "no answer" and "a calm day" must not look
 * the same. Everything it draws is also reported through [contentDescription], since a
 * canvas is invisible to a screen reader.
 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var series: TrendSeries? = null
    private var axisDescription: String = ""

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ami_chart_grid)
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Thicker than the design's hairline: this is the one mark on screen that
        // carries the actual information, and the app's users may not see a 2dp stroke.
        strokeWidth = dp(4f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ami_chart_threshold)
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)
    }

    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ami_text_secondary)
        textSize = sp(15f)
    }

    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ami_text_secondary)
        textSize = sp(18f)
        textAlign = Paint.Align.CENTER
    }

    private val path = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /**
     * @param description the spoken equivalent of the plot, built by the caller because
     *   it needs the localised dimension and range names this view does not hold.
     */
    fun show(series: TrendSeries, description: String) {
        this.series = series
        this.axisDescription = description
        contentDescription = description

        // Each dimension plots in its own colour, so a glance at the line says which
        // tab is open even when the title has scrolled off.
        val lineColor = ContextCompat.getColor(context, series.dimension.lineColorRes)
        linePaint.color = lineColor
        pointPaint.color = lineColor

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = series ?: return

        val topLabel = context.getString(current.dimension.axisTopLabelRes)
        val bottomLabel = context.getString(current.dimension.axisBottomLabelRes)

        val leftGutter = axisTextPaint.measureText("100") + dp(12f)
        val rightGutter = maxOf(
            axisTextPaint.measureText(topLabel),
            axisTextPaint.measureText(bottomLabel)
        ) + dp(12f)
        val topPad = dp(18f)
        val bottomPad = axisTextPaint.textSize + dp(16f)

        val plotLeft = paddingStart + leftGutter
        val plotRight = width - paddingEnd - rightGutter
        val plotTop = paddingTop + topPad
        val plotBottom = height - paddingBottom - bottomPad
        if (plotRight <= plotLeft || plotBottom <= plotTop) return

        fun yFor(value: Float): Float =
            plotBottom - (value / WellbeingEntry.SCALE_MAX.toFloat()) * (plotBottom - plotTop)

        // Horizontal gridlines at 0/25/50/75/100 with their labels.
        for (step in 0..4) {
            val value = step * 25f
            val y = yFor(value)
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            val label = value.toInt().toString()
            canvas.drawText(
                label,
                plotLeft - dp(8f) - axisTextPaint.measureText(label),
                y + axisTextPaint.textSize / 3f,
                axisTextPaint
            )
        }

        // The two ends of the scale, named rather than numbered. Which name goes on top
        // depends on the dimension: 100 is the bad end for stress and the good end for
        // sleep and connection.
        canvas.drawText(
            topLabel, plotRight + dp(8f), yFor(100f) + axisTextPaint.textSize / 3f, axisTextPaint
        )
        canvas.drawText(
            bottomLabel, plotRight + dp(8f), yFor(0f) + axisTextPaint.textSize / 3f, axisTextPaint
        )

        if (!current.hasData) {
            canvas.drawText(
                context.getString(R.string.wellbeing_chart_empty),
                (plotLeft + plotRight) / 2f,
                (plotTop + plotBottom) / 2f,
                emptyTextPaint
            )
            return
        }

        // The "seek help" rule, where the dimension has one. Drawn the same whether it
        // is a ceiling (stress) or a floor (connection) - the axis labels carry which.
        current.dimension.seekHelpAt?.let { threshold ->
            val y = yFor(threshold.toFloat())
            path.reset()
            path.moveTo(plotLeft, y)
            path.lineTo(plotRight, y)
            canvas.drawPath(path, thresholdPaint)
        }

        val points = current.points
        val stepX = if (points.size <= 1) 0f else (plotRight - plotLeft) / (points.size - 1)
        fun xFor(index: Int): Float =
            if (points.size <= 1) (plotLeft + plotRight) / 2f else plotLeft + stepX * index

        // Each run of consecutive present values is its own smoothed path, so a missing
        // bucket leaves a visible break instead of a straight line across the gap.
        var runStart = 0
        while (runStart < points.size) {
            if (points[runStart].value == null) {
                runStart++
                continue
            }
            var runEnd = runStart
            while (runEnd + 1 < points.size && points[runEnd + 1].value != null) runEnd++

            drawRun(canvas, points, runStart, runEnd, ::xFor, ::yFor)
            runStart = runEnd + 1
        }

        // X labels.
        points.forEachIndexed { index, point ->
            val label = point.label
            canvas.drawText(
                label,
                xFor(index) - axisTextPaint.measureText(label) / 2f,
                plotBottom + axisTextPaint.textSize + dp(10f),
                axisTextPaint
            )
        }
    }

    /**
     * Draws one unbroken run as a cubic through its points.
     *
     * Control points are placed at the midpoint of each horizontal step, which keeps the
     * curve from overshooting past a local minimum - the failure mode that would matter
     * here, since an overshoot could draw the line above the "seek help" threshold on a
     * day that never reached it.
     */
    private fun drawRun(
        canvas: Canvas,
        points: List<TrendPoint>,
        start: Int,
        end: Int,
        xFor: (Int) -> Float,
        yFor: (Float) -> Float
    ) {
        if (start == end) {
            canvas.drawCircle(xFor(start), yFor(points[start].value!!.toFloat()), dp(5f), pointPaint)
            return
        }

        path.reset()
        path.moveTo(xFor(start), yFor(points[start].value!!.toFloat()))
        for (index in start until end) {
            val x1 = xFor(index)
            val y1 = yFor(points[index].value!!.toFloat())
            val x2 = xFor(index + 1)
            val y2 = yFor(points[index + 1].value!!.toFloat())
            val midX = (x1 + x2) / 2f
            path.cubicTo(midX, y1, midX, y2, x2, y2)
        }
        canvas.drawPath(path, linePaint)

        for (index in start..end) {
            canvas.drawCircle(xFor(index), yFor(points[index].value!!.toFloat()), dp(5f), pointPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    /**
     * Via TypedValue rather than `scaledDensity`, which was deprecated once Android
     * gained non-linear font scaling - the field stopped describing the actual
     * conversion at large font sizes, which is exactly the setting this app's users run.
     */
    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}

package com.example.ami.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import com.example.ami.R
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Nature touch: deep water that answers a finger.
 *
 * The still water is painted as a gradient with slow caustic bands over it, so the surface
 * is alive before anything is touched - the reference's water is never flat, and a flat
 * fill would read as a screen that had failed to load.
 *
 * Dragging keeps laying down rings rather than waiting for a release, which is the whole
 * appeal for someone using this to settle their hands. Each touch drops three concentric
 * rings, spaced apart, because one ring reads as a button animation and three read as
 * water.
 */
class PondView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SceneView(context, attrs) {

    override val topColorRes = R.color.game_water_top
    override val bottomColorRes = R.color.game_water_bottom

    private val pond = RipplePond()

    var onTouched: ((Int) -> Unit)? = null

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val causticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val surfacePath = android.graphics.Path()

    /**
     * Rings that appear on their own, every few seconds.
     *
     * Without them the water only ever moves when touched, and a screen whose whole
     * invitation is "move your finger across the page" should look like it is already
     * doing something.
     */
    private var ambientMs = 0L

    private var maxRadius = 0f
    private var lastDragX = Float.NaN
    private var lastDragY = Float.NaN
    private var sceneTimeMs = 0L

    override fun onSceneSized(w: Float, h: Float) {
        maxRadius = min(w, h) * 0.48f
    }

    override fun onFrame(deltaMs: Long) {
        sceneTimeMs += deltaMs
        pond.advance(deltaMs)

        ambientMs += deltaMs
        if (ambientMs >= AMBIENT_INTERVAL_MS && width > 0) {
            ambientMs = 0L
            val r = kotlin.random.Random(sceneTimeMs)
            pond.touch(r.nextFloat() * width, r.nextFloat() * height, 0)
        }
    }

    override fun drawScene(canvas: Canvas) {
        drawCaustics(canvas, width.toFloat(), height.toFloat())

        pond.ripples.forEach { ripple ->
            for (ring in 0 until RINGS) {
                val fraction = ripple.radiusFraction - ring * RING_SPACING
                if (fraction <= 0f) continue

                ripplePaint.color = 0xFFFFFFFF.toInt()
                ripplePaint.alpha = (ripple.alpha * (135 - ring * 38)).toInt().coerceIn(0, 255)
                ripplePaint.strokeWidth = (1f - ripple.life) * 8f + 1.5f
                canvas.drawCircle(ripple.x, ripple.y, maxRadius * fraction, ripplePaint)
            }
        }
    }

    /**
     * The moving surface, before anything is touched.
     *
     * Two passes, because one is not enough to read as water: broad slow swells for the
     * body of it, then short bright glints scattered over the top for the sparkle. A
     * single set of wide bands - which is what this started as - just looks like
     * horizontal banding on a flat fill, and a still surface gives nobody any reason to
     * put a finger on it.
     *
     * Everything is driven by absolute scene time rather than accumulated offsets, so a
     * dropped frame shifts nothing permanently and the surface never develops a seam.
     */
    private fun drawCaustics(canvas: Canvas, w: Float, h: Float) {
        val t = sceneTimeMs / 1000f

        for (i in 0 until CAUSTIC_BANDS) {
            val phase = i * 0.7f
            val y = h * (i + 0.5f) / CAUSTIC_BANDS + sin(t * 0.25f + phase) * h * 0.025f
            causticPaint.color = 0xFFFFFFFF.toInt()
            causticPaint.alpha = (52 + 30 * sin(t * 0.4f + phase)).toInt().coerceIn(0, 255)
            causticPaint.strokeWidth = h * (0.010f + 0.008f * sin(t * 0.2f + phase))
            // A shallow arc rather than a straight line; straight bands look like scanlines.
            val bow = sin(t * 0.3f + phase) * h * 0.035f
            surfacePath.reset()
            surfacePath.moveTo(-w * 0.1f, y)
            surfacePath.quadTo(w * 0.5f, y + bow, w * 1.1f, y)
            canvas.drawPath(surfacePath, causticPaint)
        }

        val r = kotlin.random.Random(23)
        for (i in 0 until GLINTS) {
            val bx = r.nextFloat() * w
            val by = r.nextFloat() * h
            val phase = r.nextFloat() * 6.28f
            val len = w * (0.04f + r.nextFloat() * 0.09f)
            val x = bx + sin(t * 0.5f + phase) * w * 0.02f
            val y = by + sin(t * 0.33f + phase * 1.7f) * h * 0.012f
            causticPaint.color = 0xFFFFFFFF.toInt()
            causticPaint.alpha = (40 + 42 * sin(t * 0.8f + phase)).toInt().coerceIn(0, 255)
            causticPaint.strokeWidth = h * 0.005f
            surfacePath.reset()
            surfacePath.moveTo(x - len / 2f, y)
            surfacePath.quadTo(x, y - len * 0.22f, x + len / 2f, y)
            canvas.drawPath(surfacePath, causticPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drop(event.x, event.y, haptic = true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Only once the finger has actually travelled, or a slow drag would emit
                // a ripple every frame and turn the water into a solid disc.
                val far = lastDragX.isNaN() ||
                    hypot(event.x - lastDragX, event.y - lastDragY) > DRAG_SPACING
                if (far) drop(event.x, event.y, haptic = false)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastDragX = Float.NaN
                lastDragY = Float.NaN
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun drop(x: Float, y: Float, haptic: Boolean) {
        pond.touch(x, y, 0)
        lastDragX = x
        lastDragY = y
        if (haptic) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onTouched?.invoke(pond.touches)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun clear() {
        pond.clear()
        onTouched?.invoke(0)
    }

    private companion object {
        const val RINGS = 3

        /** How far behind the leading ring each trailing one sits, as a fraction. */
        const val RING_SPACING = 0.11f

        /** Minimum finger travel between ripples while dragging, in pixels. */
        const val DRAG_SPACING = 42f

        const val CAUSTIC_BANDS = 9

        /** Short bright arcs scattered over the swells. */
        const val GLINTS = 64

        const val AMBIENT_INTERVAL_MS = 2_600L
    }
}

package com.example.ami.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import com.example.ami.R

/**
 * Bubble pop: soap bubbles rising up a painted wall, each carrying a thought to be
 * got rid of.
 *
 * The bubble art is the design's own cut-out, drawn at each bubble's size, with the
 * thought on a cream label across its middle - which is what makes the thing being popped
 * legible rather than just pretty. Labels are laid out once per bubble and reused, because
 * measuring text every frame for a dozen bubbles is the one thing here that would actually
 * cost frames.
 *
 * The field never empties: a popped bubble is replaced from [ThoughtBubbles.cycle], so the
 * same thought can come round and be dismissed again. That repetition is the exercise, and
 * a field that ran out would end it after eight taps.
 */
class BubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SceneView(context, attrs) {

    override val topColorRes = R.color.game_wall_top
    override val bottomColorRes = R.color.game_wall_bottom

    private val bubbleField = BubbleField(
        population = 5,
        minRadiusFraction = 0.17f,
        maxRadiusFraction = 0.28f,
        edgeBleed = 0.45f
    )
    private val splashes = RipplePond()
    private var session = ThoughtSession()

    /** Text for each bubble, keyed by its id so a respawn gets a fresh thought. */
    private val labels = mutableMapOf<Long, String>()

    /** Reports (cleared, total) after every pop, so the screen can show progress. */
    var onPopped: ((Int, Int) -> Unit)? = null

    /** Fired once the last thought has been popped and the field is empty. */
    var onCleared: (() -> Unit)? = null

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFDF8F2.toInt()
        setShadowLayer(8f, 0f, 3f, 0x44000000)
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A2C1A.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        letterSpacing = 0.02f
    }
    private val splashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val src = Rect()
    private val dst = RectF()
    private val labelRect = RectF()

    val popped: Int get() = bubbleField.popped

    /** The bubbles currently in the air. Exposed so a test can aim at one. */
    val bubbles: List<BubbleField.Bubble> get() = bubbleField.bubbles

    /**
     * Pops at a point, exactly as a tap would. Shared by [onTouchEvent] and by tests,
     * so the path under test is the one the finger actually takes.
     */
    fun popAt(x: Float, y: Float) {
        val hit = bubbleField.popAt(x, y, respawn = session.waiting > 0) ?: return
        labels.remove(hit.id)
        session.recordPopped()
        splashes.touch(hit.x, hit.y, 0)
        assignThoughts()
        onPopped?.invoke(session.cleared, session.total)
        if (session.isComplete) onCleared?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bubbleField.resize(w.toFloat(), h.toFloat())
        assignThoughts()
    }

    /**
     * Gives every bubble on screen a thought, dropping any the session cannot supply.
     *
     * Called on resize and after each pop. Assigning at spawn rather than at draw time
     * matters: drawing would happily invent a thought for a bubble the session has already
     * run out of, and the field would never empty.
     */
    private fun assignThoughts() {
        labels.keys.retainAll(bubbleField.bubbles.map { it.id }.toSet())
        val unlabelled = bubbleField.bubbles.filter { it.id !in labels }
        unlabelled.forEach { bubble ->
            val next = session.next()
            if (next != null) labels[bubble.id] = next else bubbleField.remove(bubble)
        }
    }

    override fun onFrame(deltaMs: Long) {
        bubbleField.advance(deltaMs)
        splashes.advance(deltaMs)
    }

    override fun drawScene(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawWallTexture(canvas, w, h)

        val bubble = layer(R.drawable.game_soap_bubble)
        src.set(0, 0, bubble.width, bubble.height)

        bubbleField.bubbles.forEach { b ->
            val r = b.radius
            dst.set(b.x - r, b.y - r, b.x + r, b.y + r)
            canvas.drawBitmap(bubble, src, dst, bitmapPaint)
        }

        // Labels last, as their own pass: drawn inline, a bubble further down the list
        // paints over the text of one above it and the thought becomes unreadable.
        bubbleField.bubbles.forEach { b ->
                val text = labels[b.id] ?: return@forEach
            // Faded at both ends of the screen, so a thought never collides with the
            // headline above or the hint line below. It reads as the label drifting away
            // rather than as a clash.
            val fadeIn = ((b.y - h * LABEL_FADE_TOP) / (h * (LABEL_FADE_BOTTOM - LABEL_FADE_TOP)))
                .coerceIn(0f, 1f)
            val fadeOut = ((h * LABEL_FADE_FLOOR - b.y) / (h * (LABEL_FADE_FLOOR - LABEL_FADE_RISE)))
                .coerceIn(0f, 1f)
            val fade = minOf(fadeIn, fadeOut)
            if (fade > 0.02f) drawLabel(canvas, b.x, b.y, b.radius, text, fade)
        }

        splashes.ripples.forEach { splash ->
            splashPaint.color = 0xFFFFFFFF.toInt()
            splashPaint.alpha = (splash.alpha * 190).toInt().coerceIn(0, 255)
            splashPaint.strokeWidth = 7f * (1f - splash.life) + 1.5f
            canvas.drawCircle(splash.x, splash.y, POP_RADIUS * splash.radiusFraction, splashPaint)
        }
    }

    /**
     * A few broad, very low-contrast streaks. The reference backdrop is a painted wall
     * rather than a flat fill, and without some variation a plain gradient reads as an
     * error state on a large screen.
     */
    private fun drawWallTexture(canvas: Canvas, w: Float, h: Float) {
        val r = kotlin.random.Random(17)
        for (i in 0 until 26) {
            val y = r.nextFloat() * h
            wallPaint.color = if (i % 2 == 0) 0x0DFFFFFF else 0x0D102030
            wallPaint.strokeWidth = h * (0.01f + r.nextFloat() * 0.035f)
            canvas.drawLine(0f, y, w, y + (r.nextFloat() - 0.5f) * h * 0.08f, wallPaint)
        }
    }

    /** The cream label across the bubble's middle, sized to sit inside it. */
    private fun drawLabel(canvas: Canvas, cx: Float, cy: Float, radius: Float, text: String, fade: Float) {
        labelTextPaint.textSize = (radius * 0.26f).coerceIn(22f, 40f)
        val textWidth = labelTextPaint.measureText(text)
        val padH = labelTextPaint.textSize * 0.55f
        val padV = labelTextPaint.textSize * 0.42f

        val halfW = (textWidth / 2f + padH).coerceAtMost(radius * 1.25f)
        val halfH = labelTextPaint.textSize / 2f + padV

        // Kept inside the screen. A bubble is allowed to hang off the edge, but its label
        // is the part that has to be read, and half a sentence is worse than none.
        val clampedCx = cx.coerceIn(halfW + EDGE_PAD, (width - halfW - EDGE_PAD).coerceAtLeast(halfW + EDGE_PAD))
        labelRect.set(clampedCx - halfW, cy - halfH, clampedCx + halfW, cy + halfH)
        labelBgPaint.alpha = (fade * 255).toInt().coerceIn(0, 255)
        labelTextPaint.alpha = (fade * 255).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(labelRect, halfH * 0.55f, halfH * 0.55f, labelBgPaint)

        // Shrink to fit rather than clipping: a half-shown thought is worse than a small one.
        if (textWidth > halfW * 2f - padH) {
            labelTextPaint.textSize *= (halfW * 2f - padH) / textWidth
        }
        val baseline = cy - (labelTextPaint.descent() + labelTextPaint.ascent()) / 2f
        canvas.drawText(text, clampedCx, baseline, labelTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)

        val before = bubbleField.popped
        popAt(event.x, event.y)
        if (bubbleField.popped != before) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        // Consumed either way: a tap on open wall is a legitimate thing to do here, and
        // reporting it as unhandled would let a parent view steal the next one.
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun reset() {
        session = ThoughtSession()
        bubbleField.reset()
        splashes.clear()
        labels.clear()
        assignThoughts()
        onPopped?.invoke(session.cleared, session.total)
    }

    private companion object {
        /** How wide the burst ring grows, in pixels. */
        const val POP_RADIUS = 190f

        /** Minimum gap between a label and the screen edge. */
        const val EDGE_PAD = 14f

        /** Labels are fully faded above this fraction of the height, and solid below it. */
        const val LABEL_FADE_TOP = 0.16f
        const val LABEL_FADE_BOTTOM = 0.30f

        /** And again at the foot, where the hint line sits. */
        const val LABEL_FADE_RISE = 0.80f
        const val LABEL_FADE_FLOOR = 0.90f
    }
}

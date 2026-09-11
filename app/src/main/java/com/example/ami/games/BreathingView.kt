package com.example.ami.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import com.example.ami.R
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The breathing scene: sky, grass, a dandelion, and a phase dial along the bottom.
 *
 * The dandelion is the exercise made visible. Its seeds sit on the head through the inhale
 * and the hold, come away on the exhale, and drift back during the rest - so someone can
 * follow the whole cycle without reading a word, with their glasses off, or from across
 * the room. That is also why there is no countdown anywhere on screen: a number turns
 * breathing into a task with a deadline.
 *
 * Seeds are released on a stagger rather than all at once. A head that vanishes in one
 * frame reads as a glitch; one that comes apart over a couple of seconds reads as breath.
 *
 * Everything is a function of [elapsedMs] through [BreathingSession.momentAt] - nothing
 * accumulates frame to frame - so a dropped frame, a pause or a rotation cannot leave the
 * seeds and the spoken cue disagreeing about which phase it is.
 */
class BreathingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SceneView(context, attrs) {

    override val topColorRes = R.color.game_sky_top
    override val bottomColorRes = R.color.game_sky_bottom

    var session: BreathingSession = BreathingSession()

    /** Set by the activity each frame; the view never advances it itself. */
    var elapsedMs: Long = 0L

    /** False until the person presses start, so the dial reads "Ready" rather than a phase. */
    var started: Boolean = false

    /**
     * Ambient clock, separate from the breath clock.
     *
     * The clouds drift whether or not an exercise is running, so the scene is alive when
     * someone opens it rather than a still image that only starts moving once they commit
     * to the exercise.
     */
    private var sceneTimeMs = 0L

    private val random = Random(4)

    private val grassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bladePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val puffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF3F7EE.toInt() }
    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dialRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x44FFFFFF
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFF6E2.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        letterSpacing = 0.12f
        setShadowLayer(9f, 0f, 3f, 0x77203040)
    }

    private class Seed(
        val angle: Float,
        val radiusFraction: Float,
        val scale: Float,
        /** Fraction of the exhale that passes before this one lets go. */
        val delay: Float,
        val spin: Float,
        val drift: Float
    )

    private val seeds = List(SEED_COUNT) {
        Seed(
            // Spread evenly round the head with only a little jitter. Fully random angles
            // clump, and a clump reads as a bald patch on the clock.
            angle = it * (6.2832f / SEED_COUNT) + (random.nextFloat() - 0.5f) * 0.16f,
            radiusFraction = 0.86f + random.nextFloat() * 0.28f,
            scale = 0.82f + random.nextFloat() * 0.34f,
            delay = random.nextFloat() * 0.55f,
            spin = (random.nextFloat() - 0.5f) * 260f,
            drift = 0.7f + random.nextFloat() * 0.6f
        )
    }

    private class Cloud(val xFraction: Float, val yFraction: Float, val scale: Float, val alpha: Int)

    private val clouds = listOf(
        Cloud(0.12f, 0.14f, 0.55f, 150),
        Cloud(0.68f, 0.09f, 0.38f, 120),
        Cloud(0.44f, 0.26f, 0.30f, 90)
    )

    private var grassTop = 0f
    private val src = Rect()
    private val dst = RectF()

    override fun onSceneSized(w: Float, h: Float) {
        grassTop = h * GRASS_TOP_FRACTION
        grassPaint.shader = android.graphics.LinearGradient(
            0f, grassTop, 0f, h,
            0xFF7ED13A.toInt(), 0xFF2F5C1B.toInt(),
            android.graphics.Shader.TileMode.CLAMP
        )
    }

    override fun onFrame(deltaMs: Long) {
        // The breath clock belongs to the activity; only the ambient drift ticks here.
        sceneTimeMs += deltaMs
    }

    override fun drawScene(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val moment = session.momentAt(elapsedMs)

        drawClouds(canvas, w, h)
        drawGrass(canvas, w, h)
        drawDandelion(canvas, w, h, moment)
        drawDial(canvas, w, h, moment)
    }

    private fun drawClouds(canvas: Canvas, w: Float, h: Float) {
        val cloud = layer(R.drawable.game_cloud)
        src.set(0, 0, cloud.width, cloud.height)
        clouds.forEach { c ->
            val cw = w * c.scale
            val ch = cw * cloud.height / cloud.width
            // A slow drift keyed to elapsed time, wrapping across the width.
            val x = ((c.xFraction * w + sceneTimeMs / 90f) % (w + cw)) - cw
            bitmapPaint.alpha = c.alpha
            // Drawn twice, one period apart. With a single copy the cloud is clipped dead
            // straight at the right edge as it wraps, which shows up as a hard-edged
            // wedge of white sitting in the sky.
            for (offset in floatArrayOf(-(w + cw), 0f, w + cw)) {
                val left = x + offset
                if (left > w || left + cw < 0f) continue
                dst.set(left, h * c.yFraction, left + cw, h * c.yFraction + ch)
                canvas.drawBitmap(cloud, src, dst, bitmapPaint)
            }
        }
        bitmapPaint.alpha = 255
    }

    /**
     * The grass band: a gradient, then blades, then the little dandelion heads dotted
     * through it that the reference has.
     */
    private fun drawGrass(canvas: Canvas, w: Float, h: Float) {
        canvas.drawRect(0f, grassTop, w, h, grassPaint)

        val blades = (w / 3.2f).toInt().coerceIn(90, 420)
        val r = Random(11)
        for (i in 0 until blades) {
            val x = r.nextFloat() * w
            val baseY = grassTop + r.nextFloat() * (h - grassTop)
            val len = (h - grassTop) * (0.16f + r.nextFloat() * 0.30f)
            val lean = (r.nextFloat() - 0.5f) * len * 0.5f
            // Darker near the bottom of the band so the grass reads as having depth.
            val shade = ((baseY - grassTop) / (h - grassTop)).coerceIn(0f, 1f)
            bladePaint.color = blend(0xFF8FDA4B.toInt(), 0xFF2C5718.toInt(), shade)
            bladePaint.strokeWidth = 2.5f + r.nextFloat() * 3.5f
            canvas.drawLine(x, baseY, x + lean, baseY - len, bladePaint)
        }

        val puffs = (w / 90f).toInt().coerceIn(4, 12)
        val pr = Random(29)
        for (i in 0 until puffs) {
            val x = pr.nextFloat() * w
            val y = grassTop + pr.nextFloat() * (h - grassTop) * 0.9f
            val rad = w * (0.008f + pr.nextFloat() * 0.008f)
            puffPaint.alpha = 210
            canvas.drawCircle(x, y, rad, puffPaint)
        }
    }

    /**
     * The stem, then each seed either sitting on the head or on its way off it.
     *
     * `release` is 0 while attached and ramps to 1 as the seed blows away; the stagger
     * comes from each seed's own [Seed.delay] against the exhale's progress.
     */
    private fun drawDandelion(canvas: Canvas, w: Float, h: Float, moment: BreathingSession.Moment) {
        val stem = layer(R.drawable.game_dandelion_stem)
        val stemH = h * 0.46f
        val stemW = stemH * stem.width / stem.height
        val stemLeft = w * 0.06f
        val stemTop = grassTop - stemH * 0.82f
        src.set(0, 0, stem.width, stem.height)
        dst.set(stemLeft, stemTop, stemLeft + stemW, stemTop + stemH)
        canvas.drawBitmap(stem, src, dst, bitmapPaint)

        // The receptacle sits at the top of the stem art; every stalk converges there.
        val headX = stemLeft + stemW * 0.74f
        val headY = stemTop + stemH * 0.10f

        val seed = layer(R.drawable.game_dandelion_seed)
        src.set(0, 0, seed.width, seed.height)

        val exhale = if (moment.phase == BreathPhase.EXHALE) moment.progress else -1f

        seeds.forEach { s ->
            val release = when {
                !started -> 0f
                exhale >= 0f -> ((exhale - s.delay) / (1f - s.delay).coerceAtLeast(0.05f)).coerceIn(0f, 1f)
                // Through the rest they drift back on, so the next inhale starts whole.
                moment.phase == BreathPhase.REST -> 1f - moment.progress.coerceIn(0f, 1f)
                else -> 0f
            }

            val alpha = ((1f - release * 0.85f) * 255).toInt().coerceIn(0, 255)
            if (alpha <= 4) return@forEach

            // Up and to the right, the way the wind runs in the reference.
            val travel = h * 0.55f * release * s.drift
            val length = h * SEED_LENGTH_FRACTION * s.scale * s.radiusFraction
            val wide = length * seed.width / seed.height

            canvas.save()
            canvas.translate(headX + travel * 0.75f, headY - travel)
            // The sprite's stalk runs to its bottom edge and the fluff sits at the top, so
            // rotating by the seed's own angle plus a quarter turn points every stalk at
            // the centre. Without this they all stand upright and the head reads as a
            // scattered halo rather than a dandelion clock.
            canvas.rotate(Math.toDegrees(s.angle.toDouble()).toFloat() + 90f + s.spin * release)
            // Anchored by the stalk tip at the origin, so the tips meet at the receptacle.
            dst.set(-wide / 2f, -length, wide / 2f, 0f)
            bitmapPaint.alpha = alpha
            canvas.drawBitmap(seed, src, dst, bitmapPaint)
            canvas.restore()
        }
        bitmapPaint.alpha = 255
    }

    /**
     * The phase dial: a soft disc rising from the bottom edge, sized by the breath.
     *
     * Centred just below the screen so only a wide, shallow cap shows - the same framing
     * as the reference, and it keeps the label clear of the gesture bar.
     */
    private fun drawDial(canvas: Canvas, w: Float, h: Float, moment: BreathingSession.Moment) {
        val cx = w / 2f
        val cy = h * 1.06f
        val radius = w * (0.34f + 0.15f * moment.circleScale)

        dialPaint.color = 0x4DFFFFFF
        canvas.drawCircle(cx, cy, radius, dialPaint)
        dialRingPaint.strokeWidth = 3f
        canvas.drawCircle(cx, cy, radius, dialRingPaint)

        drawBreathDots(canvas, w, h, moment)

        val label = when {
            !started -> READY_LABEL
            moment.isComplete -> DONE_LABEL
            else -> moment.phase.label.uppercase()
        }
        labelPaint.textSize = w * 0.055f
        canvas.drawText(label, cx, h * 0.945f, labelPaint)
    }

    /**
     * One dot per breath, filling as the exercise goes.
     *
     * Deliberately not a number or a bar. Someone following a breathing exercise should be
     * able to see roughly how far along they are without being given a figure to count
     * down - but with nothing at all, as this screen originally had, two and a half
     * minutes of breathing has no shape and no end in sight.
     */
    private fun drawBreathDots(canvas: Canvas, w: Float, h: Float, moment: BreathingSession.Moment) {
        if (!started) return
        val count = session.cycles
        val gap = w * 0.028f
        val radius = w * 0.008f
        val totalWidth = (count - 1) * gap
        var x = w / 2f - totalWidth / 2f
        val y = h * 0.885f

        for (i in 0 until count) {
            dotPaint.color = if (i < moment.cycle) 0xCCFFF6E2.toInt() else 0x4DFFFFFF
            canvas.drawCircle(x, y, radius, dotPaint)
            x += gap
        }
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun mix(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private companion object {
        const val SEED_COUNT = 26

        /** Stalk length as a fraction of screen height; this sets the clock's radius. */
        const val SEED_LENGTH_FRACTION = 0.085f
        const val GRASS_TOP_FRACTION = 0.70f
        const val DONE_LABEL = "WELL DONE"
        const val READY_LABEL = "READY"
    }
}

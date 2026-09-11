package com.example.ami.games

import kotlin.math.hypot
import kotlin.random.Random

/**
 * The bubbles in Bubble Burst: where they are, and what a tap hits.
 *
 * Designed around two things that matter for this app's users and would be wrong in an
 * ordinary casual game:
 *
 * - **There is no way to lose.** A bubble that drifts off the top is not a miss; it simply
 *   comes back from the bottom. There is no timer, no score to fall, and no failure sound.
 *   The count only ever goes up, because the point is that popping them feels good, not
 *   that the person is being measured.
 * - **Taps are forgiven generously.** [TOUCH_FORGIVENESS] widens every bubble's hit area
 *   well past what is drawn. Someone with a tremor or thick fingertips should never have
 *   to aim twice, and nothing is lost by being lenient when a miss costs nothing anyway.
 *
 * Pure Kotlin, driven by explicit deltas, so the motion and the hit-testing can be tested
 * without a screen or a frame clock.
 */
class BubbleField(
    private val random: Random = Random.Default,
    /** How many bubbles are in the air at once. */
    private val population: Int = DEFAULT_POPULATION,
    /**
     * Bubble size as a fraction of the field's width.
     *
     * Parameterised because the thought bubbles have to be large enough to carry a
     * readable label across the middle, which is far bigger than a plain popping game
     * would want. Sized in fractions of width rather than pixels so a bubble stays
     * equally tappable on any screen.
     */
    private val minRadiusFraction: Float = MIN_RADIUS_FRACTION,
    private val maxRadiusFraction: Float = MAX_RADIUS_FRACTION,
    /**
     * How far a bubble may hang off the left or right edge, as a fraction of its radius.
     *
     * Zero keeps every bubble wholly on screen. That is right for small bubbles, but a
     * large one confined that way can only ever spawn in a narrow strip down the middle -
     * with a radius of a third of the width, the usable range is a third of the width -
     * so they pile up in a column and their labels overlap. Letting them bleed past the
     * edges restores the spread, and matches the reference, where bubbles are cut off by
     * the sides of the screen.
     */
    private val edgeBleed: Float = 0f
) {

    class Bubble(
        val id: Long,
        var x: Float,
        var y: Float,
        val radius: Float,
        /** Pixels per second, upward. */
        val riseSpeed: Float,
        /** Index into the view's palette, so colour choice stays a drawing concern. */
        val colorIndex: Int,
        /** Horizontal drift, which keeps them from marching in straight lines. */
        val swayAmplitude: Float,
        val swayPhase: Float
    ) {
        /** Where it started horizontally; sway is applied around this. */
        var baseX: Float = x
    }

    private val _bubbles = mutableListOf<Bubble>()
    val bubbles: List<Bubble> get() = _bubbles

    var popped: Int = 0
        private set

    private var width = 0f
    private var height = 0f
    private var nextId = 1L
    private var elapsedMs = 0L

    /** Called when the view is measured, and on rotation. Repopulates from scratch. */
    fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
        _bubbles.clear()
        if (width <= 0f || height <= 0f) return
        repeat(population) {
            // Spread the first set through the screen rather than all at the bottom, so
            // the field looks alive the instant it opens instead of filling up.
            _bubbles.add(spawn(startY = random.nextFloat() * height))
        }
    }

    fun advance(deltaMs: Long) {
        if (deltaMs <= 0 || _bubbles.isEmpty()) return
        elapsedMs += deltaMs
        val seconds = deltaMs / 1000f

        _bubbles.forEachIndexed { index, bubble ->
            bubble.y -= bubble.riseSpeed * seconds
            // Sway is a function of absolute time, not accumulated per-frame drift, so
            // a slow frame cannot push a bubble permanently sideways.
            val sway = bubble.swayAmplitude *
                kotlin.math.sin(elapsedMs / 1000f + bubble.swayPhase)
            val margin = bubble.radius * (1f - edgeBleed)
            bubble.x = (bubble.baseX + sway).coerceIn(margin, (width - margin).coerceAtLeast(margin))

            if (bubble.y + bubble.radius < 0f) {
                // Repositioned in place rather than replaced. A replacement gets a new id,
                // and the caller keys each bubble's label off that id - so a bubble that
                // merely drifted off the top would come back carrying a different thought
                // and quietly consume one from the session's pool.
                bubble.y = height + bubble.radius
                bubble.baseX = margin + random.nextFloat() * (width - 2 * margin).coerceAtLeast(1f)
                bubble.x = bubble.baseX
            }
        }
    }

    /**
     * Pops the bubble under a tap, or returns null if the tap was nowhere near one.
     *
     * When forgiving hit areas overlap, the bubble whose *centre* is nearest wins - that
     * is the one the person was looking at.
     */
    /**
     * @param respawn whether to send a fresh bubble up from the bottom in its place.
     *   True keeps the field permanently full, which is right for an endless game. False
     *   lets it empty out, which is what gives the thought-popping exercise an ending -
     *   see [com.example.ami.games.ThoughtSession].
     */
    fun popAt(x: Float, y: Float, respawn: Boolean = true): Bubble? {
        val hit = _bubbles
            .filter { hypot(it.x - x, it.y - y) <= it.radius * TOUCH_FORGIVENESS }
            .minByOrNull { hypot(it.x - x, it.y - y) }
            ?: return null

        popped++
        val index = _bubbles.indexOf(hit)
        if (respawn) {
            _bubbles[index] = spawn(startY = height + hit.radius)
        } else {
            _bubbles.removeAt(index)
        }
        return hit
    }

    /** Takes one bubble out of the air without counting it as popped. */
    fun remove(bubble: Bubble) {
        _bubbles.remove(bubble)
    }

    fun reset() {
        popped = 0
        resize(width, height)
    }

    private fun spawn(startY: Float): Bubble {
        val radius = minRadiusFraction * width +
            random.nextFloat() * (maxRadiusFraction - minRadiusFraction) * width
        val margin = radius * (1f - edgeBleed)
        val span = (width - 2 * margin).coerceAtLeast(1f)
        // Best of several candidates, picking whichever sits furthest from the bubbles
        // already in the air. Pure uniform sampling clumps often enough to matter at this
        // population - five bubbles all landing in the left half is perfectly likely - and
        // a clump means overlapping labels, which is the one thing that makes the thought
        // unreadable.
        val x = (0 until SPAWN_CANDIDATES)
            .map { margin + random.nextFloat() * span }
            .maxByOrNull { candidate ->
                _bubbles.minOfOrNull { kotlin.math.abs(it.x - candidate) } ?: Float.MAX_VALUE
            } ?: (margin + random.nextFloat() * span)
        return Bubble(
            id = nextId++,
            x = x,
            y = startY,
            radius = radius,
            // Bigger bubbles rise slower, which reads as "heavier" and, usefully, keeps
            // the easiest targets on screen longest.
            riseSpeed = (MAX_SPEED - (radius / width) * SPEED_SPREAD).coerceAtLeast(MIN_SPEED),
            colorIndex = random.nextInt(PALETTE_SIZE),
            swayAmplitude = radius * 0.35f,
            swayPhase = random.nextFloat() * 6.28f
        ).also { it.baseX = x }
    }

    companion object {
        const val PALETTE_SIZE = 5

        private const val DEFAULT_POPULATION = 9

        /** Hit radius multiplier. Deliberately large; see the class comment. */
        const val TOUCH_FORGIVENESS = 1.45f

        // Defaults; see the constructor for why these are adjustable.
        const val MIN_RADIUS_FRACTION = 0.075f
        const val MAX_RADIUS_FRACTION = 0.135f

        /** How many positions to try when placing a bubble; see spawn(). */
        private const val SPAWN_CANDIDATES = 6

        private const val MAX_SPEED = 70f
        private const val MIN_SPEED = 22f
        private const val SPEED_SPREAD = 320f
    }
}

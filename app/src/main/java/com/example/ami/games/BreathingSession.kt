package com.example.ami.games

/**
 * A paced breathing exercise, as a pure function of elapsed time.
 *
 * Of the four relaxation activities this is the one with an actual physiological basis:
 * an exhale longer than the inhale lengthens the gap between heartbeats and shifts the
 * balance toward the parasympathetic ("rest") side. That is why [CALM] is not symmetric,
 * and why the exhale must never be shortened to make a cycle fit more neatly.
 *
 * Time-driven rather than state-driven on purpose. The view asks "what should I be showing
 * at 7,300ms?" and gets a complete answer, so a dropped frame, a screen rotation or the
 * activity being paused and resumed cannot leave the animation and the spoken cue
 * disagreeing about which phase it is - which is exactly the sort of jitter that makes a
 * calming exercise stressful.
 */
enum class BreathPhase(val label: String, val spoken: String) {
    INHALE("Breathe in", "Breathe in"),
    HOLD("Hold", "Hold"),
    EXHALE("Breathe out", "Breathe out, slowly"),
    REST("Rest", "And rest")
}

data class BreathingPattern(
    val inhaleMs: Long,
    val holdMs: Long,
    val exhaleMs: Long,
    val restMs: Long
) {
    val cycleMs: Long = inhaleMs + holdMs + exhaleMs + restMs

    init {
        require(cycleMs > 0) { "a breathing cycle must take some time" }
    }

    companion object {
        /**
         * Gentle and achievable for an older person, including one short of breath.
         *
         * 4 in / 2 hold / 6 out / 1 rest. The hold is deliberately short - long breath
         * holds are uncomfortable and can feel alarming, which defeats the purpose.
         */
        val CALM = BreathingPattern(inhaleMs = 4_000, holdMs = 2_000, exhaleMs = 6_000, restMs = 1_000)
    }
}

class BreathingSession(
    val pattern: BreathingPattern = BreathingPattern.CALM,
    val cycles: Int = DEFAULT_CYCLES
) {

    /** Everything the view and the voice need for one instant. */
    data class Moment(
        val phase: BreathPhase,
        /** 0f at the start of this phase, approaching 1f at its end. */
        val progress: Float,
        /** 0-based; equals [cycles] once finished. */
        val cycle: Int,
        val isComplete: Boolean
    ) {
        /**
         * How big to draw the circle, 0..1.
         *
         * Grows through the inhale, stays full through the hold, shrinks through the
         * exhale, and rests small. Never reaches 0 - a circle that vanishes reads as
         * something having gone wrong.
         */
        val circleScale: Float
            get() = when (phase) {
                BreathPhase.INHALE -> MIN_SCALE + (1f - MIN_SCALE) * progress
                BreathPhase.HOLD -> 1f
                BreathPhase.EXHALE -> 1f - (1f - MIN_SCALE) * progress
                BreathPhase.REST -> MIN_SCALE
            }
    }

    val totalMs: Long = pattern.cycleMs * cycles

    fun momentAt(elapsedMs: Long): Moment {
        val clamped = elapsedMs.coerceAtLeast(0L)

        if (clamped >= totalMs) {
            return Moment(BreathPhase.REST, 1f, cycles, isComplete = true)
        }

        val cycle = (clamped / pattern.cycleMs).toInt()
        val into = clamped % pattern.cycleMs

        var start = 0L
        for (phase in BreathPhase.entries) {
            val length = lengthOf(phase)
            // A zero-length phase is skipped rather than briefly shown; a pattern with no
            // hold should never flash the word "Hold".
            if (length > 0 && into < start + length) {
                return Moment(phase, (into - start).toFloat() / length, cycle, isComplete = false)
            }
            start += length
        }

        // Only reachable through floating-point-free integer rounding at the very end of
        // a cycle; treat it as the close of the exhale rather than throwing.
        return Moment(BreathPhase.REST, 1f, cycle, isComplete = false)
    }

    private fun lengthOf(phase: BreathPhase): Long = when (phase) {
        BreathPhase.INHALE -> pattern.inhaleMs
        BreathPhase.HOLD -> pattern.holdMs
        BreathPhase.EXHALE -> pattern.exhaleMs
        BreathPhase.REST -> pattern.restMs
    }

    companion object {
        /** About two and a half minutes at [BreathingPattern.CALM]. */
        const val DEFAULT_CYCLES = 12

        private const val MIN_SCALE = 0.35f
    }
}

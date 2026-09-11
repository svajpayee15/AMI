package com.example.ami.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreathingSessionTest {

    // 4 in / 2 hold / 6 out / 1 rest = 13s per cycle.
    private val session = BreathingSession(BreathingPattern.CALM, cycles = 3)

    @Test
    fun `the exhale is longer than the inhale`() {
        // Not a style choice: the longer exhale is what actually shifts someone toward
        // the parasympathetic side. If this ever fails, the exercise stopped working.
        assertTrue(BreathingPattern.CALM.exhaleMs > BreathingPattern.CALM.inhaleMs)
    }

    @Test
    fun `phases follow each other in order within a cycle`() {
        assertEquals(BreathPhase.INHALE, session.momentAt(0).phase)
        assertEquals(BreathPhase.INHALE, session.momentAt(3_999).phase)
        assertEquals(BreathPhase.HOLD, session.momentAt(4_000).phase)
        assertEquals(BreathPhase.HOLD, session.momentAt(5_999).phase)
        assertEquals(BreathPhase.EXHALE, session.momentAt(6_000).phase)
        assertEquals(BreathPhase.EXHALE, session.momentAt(11_999).phase)
        assertEquals(BreathPhase.REST, session.momentAt(12_000).phase)
        assertEquals(BreathPhase.REST, session.momentAt(12_999).phase)
    }

    @Test
    fun `the next cycle starts cleanly on a fresh inhale`() {
        val moment = session.momentAt(13_000)
        assertEquals(BreathPhase.INHALE, moment.phase)
        assertEquals(0f, moment.progress, 0.001f)
        assertEquals(1, moment.cycle)
    }

    @Test
    fun `progress runs from zero to one across a phase`() {
        assertEquals(0f, session.momentAt(0).progress, 0.001f)
        assertEquals(0.5f, session.momentAt(2_000).progress, 0.001f)
        // Start of the hold, not the end of the inhale.
        assertEquals(0f, session.momentAt(4_000).progress, 0.001f)
    }

    @Test
    fun `cycles are counted from zero`() {
        assertEquals(0, session.momentAt(0).cycle)
        assertEquals(0, session.momentAt(12_999).cycle)
        assertEquals(1, session.momentAt(13_000).cycle)
        assertEquals(2, session.momentAt(26_000).cycle)
    }

    @Test
    fun `the session completes after its last cycle and stays complete`() {
        assertEquals(39_000L, session.totalMs)
        assertFalse(session.momentAt(38_999).isComplete)
        assertTrue(session.momentAt(39_000).isComplete)
        assertTrue(session.momentAt(999_999).isComplete)
    }

    @Test
    fun `a negative elapsed time is treated as the start`() {
        // Guards against a clock that briefly runs backwards after a resume.
        val moment = session.momentAt(-500)
        assertEquals(BreathPhase.INHALE, moment.phase)
        assertFalse(moment.isComplete)
    }

    // --- The circle -------------------------------------------------------------------

    @Test
    fun `the circle grows through the inhale and shrinks through the exhale`() {
        val startOfInhale = session.momentAt(0).circleScale
        val endOfInhale = session.momentAt(3_999).circleScale
        assertTrue(endOfInhale > startOfInhale)

        val startOfExhale = session.momentAt(6_000).circleScale
        val endOfExhale = session.momentAt(11_999).circleScale
        assertTrue(endOfExhale < startOfExhale)
    }

    @Test
    fun `the circle holds steady at full size through the hold`() {
        assertEquals(1f, session.momentAt(4_000).circleScale, 0.001f)
        assertEquals(1f, session.momentAt(5_999).circleScale, 0.001f)
    }

    @Test
    fun `the circle never disappears`() {
        // A circle that shrinks to nothing reads as a crash, not as an exhale.
        for (t in 0L until session.totalMs step 100L) {
            assertTrue("vanished at ${t}ms", session.momentAt(t).circleScale > 0.1f)
        }
    }

    // --- Odd patterns ------------------------------------------------------------------

    @Test
    fun `a pattern with no hold never shows the word Hold`() {
        val noHold = BreathingSession(
            BreathingPattern(inhaleMs = 4_000, holdMs = 0, exhaleMs = 6_000, restMs = 0),
            cycles = 2
        )
        for (t in 0L until noHold.totalMs step 50L) {
            val phase = noHold.momentAt(t).phase
            assertTrue("saw $phase at ${t}ms", phase == BreathPhase.INHALE || phase == BreathPhase.EXHALE)
        }
    }

    @Test
    fun `a pattern with no time at all is rejected rather than dividing by zero`() {
        try {
            BreathingPattern(0, 0, 0, 0)
            throw AssertionError("expected an empty pattern to be rejected")
        } catch (expected: IllegalArgumentException) {
            // as intended
        }
    }
}

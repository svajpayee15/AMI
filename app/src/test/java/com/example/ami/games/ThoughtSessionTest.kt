package com.example.ami.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ThoughtSessionTest {

    private fun session(size: Int = 6, pool: List<String> = listOf("a", "b", "c")) =
        ThoughtSession(pool = pool, size = size, random = Random(4))

    @Test
    fun `a session holds exactly the number of thoughts asked for`() {
        assertEquals(6, session().total)
        assertEquals(6, session().waiting)
    }

    @Test
    fun `a pool smaller than the session repeats to fill it`() {
        // Three thoughts, twelve to get through: each has to come round more than once.
        val s = ThoughtSession(pool = listOf("a", "b", "c"), size = 12, random = Random(1))
        val drawn = (0 until 12).mapNotNull { s.next() }
        assertEquals(12, drawn.size)
        assertTrue(drawn.toSet().containsAll(listOf("a", "b", "c")))
    }

    @Test
    fun `a pool larger than the session is trimmed, not overfilled`() {
        val s = ThoughtSession(pool = ThoughtBubbles.THOUGHTS, size = 3, random = Random(2))
        assertEquals(3, s.total)
        assertEquals(3, (0 until 10).mapNotNull { s.next() }.size)
    }

    @Test
    fun `next runs out rather than looping forever`() {
        // This is what lets the bubble field empty and the exercise end.
        val s = session(size = 2)
        assertNotNull(s.next())
        assertNotNull(s.next())
        assertNull(s.next())
    }

    // --- Completion ---------------------------------------------------------------------

    @Test
    fun `a session is not complete merely because the queue is empty`() {
        // A thought handed to a bubble has left the queue but is still on screen. Calling
        // it done here would close the exercise with bubbles still floating.
        val s = session(size = 2)
        s.next()
        s.next()
        assertEquals(0, s.waiting)
        assertFalse(s.isComplete)
    }

    @Test
    fun `a session completes once every thought has been popped`() {
        val s = session(size = 2)
        s.next(); s.recordPopped()
        assertFalse(s.isComplete)
        s.next(); s.recordPopped()
        assertTrue(s.isComplete)
        assertEquals(2, s.cleared)
    }

    @Test
    fun `cleared never runs past the total`() {
        val s = session(size = 2)
        repeat(10) { s.recordPopped() }
        assertEquals(2, s.cleared)
    }

    @Test
    fun `an empty pool is rejected rather than producing a session that cannot end`() {
        try {
            ThoughtSession(pool = emptyList())
            throw AssertionError("expected an empty pool to be rejected")
        } catch (expected: IllegalArgumentException) {
            // as intended
        }
    }

    @Test
    fun `the default session is long enough to be worth doing and short enough to finish`() {
        val s = ThoughtSession()
        assertEquals(ThoughtSession.SESSION_SIZE, s.total)
        assertTrue("too short to do anything", s.total >= 8)
        assertTrue("long enough to become a chore", s.total <= 20)
    }
}

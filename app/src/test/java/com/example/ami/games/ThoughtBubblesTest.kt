package com.example.ami.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThoughtBubblesTest {

    @Test
    fun `the cycle never runs out`() {
        // A field that emptied would end the exercise after eight taps; the same thought
        // coming round and being dismissed again is the point of it.
        val cycle = ThoughtBubbles.cycle(seed = 1)
        val drawn = (0 until ThoughtBubbles.THOUGHTS.size * 4).map { cycle.next() }
        assertEquals(ThoughtBubbles.THOUGHTS.size * 4, drawn.size)
        assertTrue(drawn.all { it in ThoughtBubbles.THOUGHTS })
    }

    @Test
    fun `every thought appears within the first pass`() {
        val cycle = ThoughtBubbles.cycle(seed = 3)
        val firstPass = (ThoughtBubbles.THOUGHTS.indices).map { cycle.next() }.toSet()
        assertEquals(ThoughtBubbles.THOUGHTS.toSet(), firstPass)
    }

    @Test
    fun `the order is shuffled, not the declaration order`() {
        // Same list every session would make the exercise feel like a script.
        val a = (0 until ThoughtBubbles.THOUGHTS.size).map { ThoughtBubbles.cycle(seed = 1).next() }
        val b = (0 until ThoughtBubbles.THOUGHTS.size).map { ThoughtBubbles.cycle(seed = 9).next() }
        assertTrue("shuffling had no effect", a != b || ThoughtBubbles.THOUGHTS.size == 1)
    }

    @Test
    fun `no thought is about self-harm or dying`() {
        // The safety rule this screen is built around: a tap-to-pop game is the wrong
        // place for those, and rendering one as a toy would be cruel. Anything in that
        // territory belongs on the caretaker agent's red-flag path, with a person at the
        // end of it - not here.
        val banned = listOf(
            "kill", "die", "dying", "dead", "suicide", "end it", "better off",
            "hurt myself", "harm myself", "not worth living", "want to go"
        )
        ThoughtBubbles.THOUGHTS.forEach { thought ->
            val lower = thought.lowercase()
            banned.forEach { phrase ->
                assertFalse("\"$thought\" contains \"$phrase\"", lower.contains(phrase))
            }
        }
    }

    @Test
    fun `thoughts are short enough to read on a bubble`() {
        // Anything much longer is shrunk past legibility by BubbleView's fit-to-width.
        ThoughtBubbles.THOUGHTS.forEach {
            assertTrue("\"$it\" is ${it.length} chars", it.length <= 32)
        }
    }

    @Test
    fun `thoughts are distinct`() {
        assertEquals(ThoughtBubbles.THOUGHTS.size, ThoughtBubbles.THOUGHTS.distinct().size)
    }
}

package com.example.ami.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BubbleFieldTest {

    private fun field() = BubbleField(Random(11)).apply { resize(1000f, 2000f) }

    @Test
    fun `a sized field fills with bubbles spread over the screen`() {
        val field = field()
        assertTrue(field.bubbles.isNotEmpty())
        // Not all stacked at the bottom waiting to float up.
        assertTrue(field.bubbles.map { it.y }.distinct().size > 1)
    }

    @Test
    fun `an unmeasured field is empty rather than dividing by zero`() {
        val field = BubbleField(Random(1))
        field.resize(0f, 0f)
        assertTrue(field.bubbles.isEmpty())
        assertNull(field.popAt(10f, 10f))
    }

    @Test
    fun `bubbles stay within the screen horizontally`() {
        val field = field()
        repeat(50) { field.advance(100) }
        field.bubbles.forEach {
            assertTrue("drifted off at x=${it.x}", it.x >= 0f && it.x <= 1000f)
        }
    }

    // --- Movement -------------------------------------------------------------------------

    @Test
    fun `bubbles rise`() {
        val field = field()
        val before = field.bubbles.associate { it.id to it.y }
        field.advance(1_000)
        // Any bubble still present must have moved up; ones that wrapped are new ids.
        val moved = field.bubbles.count { before[it.id] != null && it.y < before.getValue(it.id) }
        assertTrue("nothing rose", moved > 0)
    }

    @Test
    fun `a zero or negative delta changes nothing`() {
        val field = field()
        val before = field.bubbles.map { it.y }
        field.advance(0)
        field.advance(-100)
        assertEquals(before, field.bubbles.map { it.y })
    }

    @Test
    fun `a bubble that floats off the top comes back rather than being lost`() {
        val field = field()
        val population = field.bubbles.size
        // Far longer than any bubble takes to cross the screen.
        repeat(200) { field.advance(500) }
        assertEquals(population, field.bubbles.size)
        assertEquals(0, field.popped)  // drifting away is never a miss
    }

    // --- Popping --------------------------------------------------------------------------

    @Test
    fun `tapping a bubble pops it and counts it`() {
        val field = field()
        val target = field.bubbles.first()
        val popped = field.popAt(target.x, target.y)

        assertNotNull(popped)
        assertEquals(target.id, popped!!.id)
        assertEquals(1, field.popped)
    }

    @Test
    fun `a popped bubble is replaced so the screen never empties`() {
        val field = field()
        val population = field.bubbles.size
        repeat(20) { field.popAt(field.bubbles.first().x, field.bubbles.first().y) }
        assertEquals(population, field.bubbles.size)
        assertEquals(20, field.popped)
    }

    @Test
    fun `tapping empty water does nothing at all`() {
        val field = BubbleField(Random(2), population = 1).apply { resize(1000f, 2000f) }
        val only = field.bubbles.first()
        // Far from the single bubble.
        val miss = field.popAt(only.x + 900f, only.y + 1500f)
        assertNull(miss)
        assertEquals(0, field.popped)
    }

    @Test
    fun `a near miss still pops, because aiming twice is the frustrating part`() {
        val field = BubbleField(Random(4), population = 1).apply { resize(1000f, 2000f) }
        val bubble = field.bubbles.first()
        // Outside the drawn circle, inside the forgiving hit area.
        val justOutside = bubble.radius * 1.2f
        assertNotNull(field.popAt(bubble.x + justOutside, bubble.y))
    }

    @Test
    fun `when hit areas overlap the nearest centre wins`() {
        val field = field()
        val target = field.bubbles.minByOrNull { it.radius }!!
        val popped = field.popAt(target.x, target.y)
        assertEquals(target.id, popped?.id)
    }

    @Test
    fun `reset clears the count and refills`() {
        val field = field()
        field.popAt(field.bubbles.first().x, field.bubbles.first().y)
        assertEquals(1, field.popped)

        field.reset()
        assertEquals(0, field.popped)
        assertTrue(field.bubbles.isNotEmpty())
    }

    @Test
    fun `colours stay inside the palette the view knows about`() {
        val field = field()
        repeat(30) { field.popAt(field.bubbles.first().x, field.bubbles.first().y) }
        field.bubbles.forEach {
            assertTrue(it.colorIndex in 0 until BubbleField.PALETTE_SIZE)
        }
    }
}

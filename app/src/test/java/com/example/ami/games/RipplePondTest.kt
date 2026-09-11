package com.example.ami.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RipplePondTest {

    @Test
    fun `a touch makes a ripple`() {
        val pond = RipplePond()
        pond.touch(100f, 200f, 0)
        assertEquals(1, pond.ripples.size)
        assertEquals(1, pond.touches)
        assertEquals(100f, pond.ripples.first().x, 0.001f)
    }

    @Test
    fun `a ripple spreads outward and fades as it ages`() {
        val pond = RipplePond()
        pond.touch(0f, 0f, 0)
        val ripple = pond.ripples.first()

        assertEquals(0f, ripple.radiusFraction, 0.001f)
        assertEquals(1f, ripple.alpha, 0.001f)

        pond.advance(RipplePond.LIFETIME_MS / 2)
        assertTrue(ripple.radiusFraction > 0f)
        assertTrue(ripple.alpha < 1f)
    }

    @Test
    fun `a ripple is gone once its life is up`() {
        val pond = RipplePond()
        pond.touch(0f, 0f, 0)
        pond.advance(RipplePond.LIFETIME_MS)
        assertTrue(pond.ripples.isEmpty())
    }

    @Test
    fun `ripples expire by age, so a long pause does not leave one frozen`() {
        val pond = RipplePond()
        pond.touch(0f, 0f, 0)
        // One long delta, as if the screen had been away and come back.
        pond.advance(RipplePond.LIFETIME_MS * 10)
        assertTrue(pond.ripples.isEmpty())
    }

    @Test
    fun `only the expired ones are removed`() {
        val pond = RipplePond()
        pond.touch(0f, 0f, 0)
        pond.advance(RipplePond.LIFETIME_MS - 100)
        pond.touch(50f, 50f, 1)

        pond.advance(200)  // ages out the first, not the second
        assertEquals(1, pond.ripples.size)
        assertEquals(50f, pond.ripples.first().x, 0.001f)
    }

    @Test
    fun `drumming fingers drops the oldest rather than refusing the newest`() {
        // A tap must always do something visible, however fast they come.
        val pond = RipplePond(maxRipples = 3)
        repeat(10) { pond.touch(it.toFloat(), 0f, 0) }

        assertEquals(3, pond.ripples.size)
        assertEquals(9f, pond.ripples.last().x, 0.001f)
        assertEquals(10, pond.touches)
    }

    @Test
    fun `a zero or negative delta changes nothing`() {
        val pond = RipplePond()
        pond.touch(0f, 0f, 0)
        pond.advance(0)
        pond.advance(-500)
        assertEquals(1, pond.ripples.size)
        assertEquals(0L, pond.ripples.first().ageMs)
    }

    @Test
    fun `clear empties the pond`() {
        val pond = RipplePond()
        repeat(3) { pond.touch(0f, 0f, 0) }
        pond.clear()
        assertTrue(pond.ripples.isEmpty())
        assertEquals(0, pond.touches)
    }

    @Test
    fun `life never runs past its bounds`() {
        val pond = RipplePond()
        pond.touch(0f, 0f, 0)
        val ripple = pond.ripples.first()
        ripple.ageMs = RipplePond.LIFETIME_MS * 5
        assertEquals(1f, ripple.life, 0.001f)
        assertEquals(0f, ripple.alpha, 0.001f)
    }
}

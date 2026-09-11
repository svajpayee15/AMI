package com.example.ami.games

/**
 * Calm Pond: touch the water, watch the rings spread and fade.
 *
 * There is no goal here at all, and that is the point. The other three activities each ask
 * something of the person - follow a rhythm, aim at a bubble, hold a symbol in mind. This
 * one asks nothing, which makes it the right thing to open when someone is agitated and
 * anything with a rule in it would be one demand too many.
 *
 * Ripples expire by age rather than being counted down per frame, so pausing the screen
 * and coming back does not leave a ring frozen mid-spread.
 */
class RipplePond(private val maxRipples: Int = MAX_RIPPLES) {

    class Ripple(
        val x: Float,
        val y: Float,
        val colorIndex: Int,
        var ageMs: Long = 0L
    ) {
        /** 0f when just touched, 1f when fully spread and gone. */
        val life: Float get() = (ageMs.toFloat() / LIFETIME_MS).coerceIn(0f, 1f)

        /** Eases outward - fast at first, slowing as it widens, the way water behaves. */
        val radiusFraction: Float get() = 1f - (1f - life) * (1f - life)

        /** Fades out over the whole life, so nothing ever pops off the screen. */
        val alpha: Float get() = 1f - life
    }

    private val _ripples = mutableListOf<Ripple>()
    val ripples: List<Ripple> get() = _ripples

    var touches: Int = 0
        private set

    fun touch(x: Float, y: Float, colorIndex: Int) {
        touches++
        _ripples.add(Ripple(x, y, colorIndex))
        // Drop the oldest rather than refusing the newest: a tap must always do something
        // visible, even when someone is drumming their fingers on the screen.
        while (_ripples.size > maxRipples) _ripples.removeAt(0)
    }

    fun advance(deltaMs: Long) {
        if (deltaMs <= 0) return
        _ripples.forEach { it.ageMs += deltaMs }
        _ripples.removeAll { it.ageMs >= LIFETIME_MS }
    }

    fun clear() {
        _ripples.clear()
        touches = 0
    }

    companion object {
        const val LIFETIME_MS = 2_400L
        const val PALETTE_SIZE = 4
        private const val MAX_RIPPLES = 14
    }
}

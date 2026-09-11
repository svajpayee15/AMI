package com.example.ami.games

import kotlin.random.Random

/**
 * One run of the bubble exercise: a finite set of thoughts to get rid of, and a way to
 * know when they are gone.
 *
 * The first version of this screen refilled forever. It looked fine and felt pointless -
 * the person could tap for as long as they liked and never arrive anywhere, so there was
 * no moment of having finished and nothing to feel good about. An exercise in letting go
 * of unwanted thoughts needs a last one.
 *
 * [SESSION_SIZE] is the whole decision. Too few and it is over before it has done
 * anything; too many and clearing them becomes a chore, which is the failure mode this
 * app cannot afford. A dozen is roughly a minute of unhurried tapping.
 */
class ThoughtSession(
    pool: List<String> = ThoughtBubbles.THOUGHTS,
    private val size: Int = SESSION_SIZE,
    random: Random = Random.Default
) {

    /**
     * Built by shuffling the pool and taking as many as are needed, repeating the pool
     * when it is smaller than a session. Shuffled per repeat so the same thought does not
     * arrive twice in a row.
     */
    private val queue: ArrayDeque<String> = ArrayDeque<String>().apply {
        require(pool.isNotEmpty()) { "a session needs at least one thought" }
        while (this.size < this@ThoughtSession.size) {
            addAll(pool.shuffled(random))
        }
        while (this.size > this@ThoughtSession.size) {
            removeLast()
        }
    }

    val total: Int = queue.size

    var cleared: Int = 0
        private set

    /** Thoughts not yet handed out to a bubble. */
    val waiting: Int get() = queue.size

    /**
     * True once every thought has been popped.
     *
     * Note this is driven by [cleared], not by the queue: a thought handed to a bubble has
     * left the queue but is still on screen, and the session is not over until it has
     * actually been popped.
     */
    val isComplete: Boolean get() = cleared >= total

    /** The next thought for a bubble, or null when there are none left to hand out. */
    fun next(): String? = queue.removeFirstOrNull()

    fun recordPopped() {
        if (cleared < total) cleared++
    }

    companion object {
        /** About a minute of unhurried tapping; see the class comment. */
        const val SESSION_SIZE = 12
    }
}

package com.example.ami.games

import kotlin.random.Random

/**
 * The rules of Memory Pairs.
 *
 * Gentle on purpose: no timer, no move limit, no score. A memory game aimed at older
 * people can very easily become a test that tells someone their memory is failing, which
 * is the opposite of relaxing. Nothing here counts against the player - [moves] exists
 * only so the finish screen can say something warm, never to rank them.
 *
 * The mismatch pause is the caller's to own. [flip] reports a mismatch and leaves both
 * cards face up; the screen shows them for a moment and then calls [hideMismatched].
 * While two unmatched cards are up, further flips are [Flip.Ignored] - without that rule
 * a fast double-tap flips a third card and the pair vanishes before it has been seen,
 * which is the single most common way this kind of game frustrates someone.
 */
class MemoryBoard private constructor(private val _cards: MutableList<Card>) {

    class Card(
        val index: Int,
        val symbol: String,
        var faceUp: Boolean = false,
        var matched: Boolean = false
    )

    sealed interface Flip {
        /** The tap changed nothing: already up, already matched, or two are showing. */
        data object Ignored : Flip

        /** First card of a pair is now showing. */
        data class Revealed(val index: Int) : Flip

        data class Matched(val a: Int, val b: Int) : Flip

        /** Both are showing and do not match; call [hideMismatched] after a pause. */
        data class Mismatched(val a: Int, val b: Int) : Flip
    }

    val cards: List<Card> get() = _cards

    var moves: Int = 0
        private set

    val matchedPairs: Int get() = _cards.count { it.matched } / 2

    val totalPairs: Int get() = _cards.size / 2

    val isComplete: Boolean get() = _cards.all { it.matched }

    /** The two face-up, unmatched cards waiting to be hidden, if any. */
    private val pending: List<Card> get() = _cards.filter { it.faceUp && !it.matched }

    fun flip(index: Int): Flip {
        val card = _cards.getOrNull(index) ?: return Flip.Ignored
        if (card.matched || card.faceUp) return Flip.Ignored

        val showing = pending
        if (showing.size >= 2) return Flip.Ignored

        card.faceUp = true

        val first = showing.firstOrNull() ?: return Flip.Revealed(index)

        moves++
        return if (first.symbol == card.symbol) {
            first.matched = true
            card.matched = true
            Flip.Matched(first.index, index)
        } else {
            Flip.Mismatched(first.index, index)
        }
    }

    /** Turns the mismatched pair back over. Safe to call when there is nothing to hide. */
    fun hideMismatched() {
        pending.forEach { it.faceUp = false }
    }

    companion object {

        /**
         * Large, high-contrast and unmistakable from each other at a glance.
         *
         * Everyday objects rather than abstract shapes: "the teapot" is far easier to hold
         * in mind for one turn than "the blue hexagon", and easier still to say out loud
         * to whoever is sitting alongside.
         */
        val DEFAULT_SYMBOLS = listOf("🌻", "🍎", "🐦", "🌙", "🫖", "⭐")

        /**
         * Builds a shuffled board with each symbol appearing exactly twice.
         *
         * @param symbols one entry per pair.
         */
        fun of(
            symbols: List<String> = DEFAULT_SYMBOLS,
            random: Random = Random.Default
        ): MemoryBoard {
            require(symbols.isNotEmpty()) { "a board needs at least one pair" }
            val shuffled = (symbols + symbols).shuffled(random)
            return MemoryBoard(
                shuffled.mapIndexed { index, symbol -> Card(index, symbol) }.toMutableList()
            )
        }
    }
}

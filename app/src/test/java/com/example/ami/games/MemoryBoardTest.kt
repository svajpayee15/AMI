package com.example.ami.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MemoryBoardTest {

    private fun board(vararg symbols: String) =
        MemoryBoard.of(symbols.toList(), Random(7))

    /** Index of a card carrying [symbol], skipping [not]. */
    private fun MemoryBoard.find(symbol: String, not: Int = -1) =
        cards.first { it.symbol == symbol && it.index != not }.index

    // --- Dealing ------------------------------------------------------------------------

    @Test
    fun `every symbol appears exactly twice`() {
        val board = MemoryBoard.of(listOf("A", "B", "C"), Random(1))
        assertEquals(6, board.cards.size)
        assertEquals(3, board.totalPairs)
        board.cards.groupBy { it.symbol }.forEach { (symbol, cards) ->
            assertEquals("wrong count for $symbol", 2, cards.size)
        }
    }

    @Test
    fun `cards start face down and unmatched`() {
        val board = board("A", "B")
        assertTrue(board.cards.none { it.faceUp })
        assertTrue(board.cards.none { it.matched })
        assertFalse(board.isComplete)
        assertEquals(0, board.moves)
    }

    @Test
    fun `the default symbols are all different`() {
        // Two identical symbols in the catalogue would make an unwinnable board.
        assertEquals(
            MemoryBoard.DEFAULT_SYMBOLS.size,
            MemoryBoard.DEFAULT_SYMBOLS.distinct().size
        )
    }

    @Test
    fun `an empty symbol list is rejected`() {
        try {
            MemoryBoard.of(emptyList())
            throw AssertionError("expected an empty board to be rejected")
        } catch (expected: IllegalArgumentException) {
            // as intended
        }
    }

    // --- Matching ------------------------------------------------------------------------

    @Test
    fun `a matching pair stays face up`() {
        val board = board("A", "B")
        val first = board.find("A")
        val second = board.find("A", not = first)

        assertEquals(MemoryBoard.Flip.Revealed(first), board.flip(first))
        assertEquals(MemoryBoard.Flip.Matched(first, second), board.flip(second))

        assertTrue(board.cards[first].matched)
        assertTrue(board.cards[second].matched)
        assertTrue(board.cards[first].faceUp)
        assertEquals(1, board.matchedPairs)
    }

    @Test
    fun `a mismatch is reported and then hidden by the caller`() {
        val board = board("A", "B")
        val a = board.find("A")
        val b = board.find("B")

        board.flip(a)
        assertEquals(MemoryBoard.Flip.Mismatched(a, b), board.flip(b))

        // Still showing, so the person gets to see what they turned over.
        assertTrue(board.cards[a].faceUp)
        assertTrue(board.cards[b].faceUp)

        board.hideMismatched()
        assertFalse(board.cards[a].faceUp)
        assertFalse(board.cards[b].faceUp)
    }

    @Test
    fun `hiding leaves matched cards alone`() {
        val board = board("A", "B")
        val a1 = board.find("A")
        board.flip(a1)
        board.flip(board.find("A", not = a1))

        board.hideMismatched()
        assertTrue(board.cards[a1].faceUp)
    }

    @Test
    fun `hiding when nothing is showing is harmless`() {
        val board = board("A", "B")
        board.hideMismatched()
        assertTrue(board.cards.none { it.faceUp })
    }

    // --- Taps that must do nothing --------------------------------------------------------

    @Test
    fun `a third flip is ignored while two cards are showing`() {
        // The important one: without this, a double-tap turns over a third card and the
        // mismatched pair disappears before it has been seen.
        val board = MemoryBoard.of(listOf("A", "B", "C"), Random(3))
        val a = board.find("A")
        val b = board.find("B")
        val c = board.find("C")

        board.flip(a)
        board.flip(b)
        assertEquals(MemoryBoard.Flip.Ignored, board.flip(c))
        assertFalse(board.cards[c].faceUp)
    }

    @Test
    fun `flipping the same card twice is ignored, not counted as a pair`() {
        val board = board("A", "B")
        val a = board.find("A")
        board.flip(a)
        assertEquals(MemoryBoard.Flip.Ignored, board.flip(a))
        assertEquals(0, board.moves)
    }

    @Test
    fun `an already matched card cannot be flipped again`() {
        val board = board("A", "B")
        val a1 = board.find("A")
        val a2 = board.find("A", not = a1)
        board.flip(a1)
        board.flip(a2)
        assertEquals(MemoryBoard.Flip.Ignored, board.flip(a1))
    }

    @Test
    fun `an index off the board is ignored rather than crashing`() {
        val board = board("A")
        assertEquals(MemoryBoard.Flip.Ignored, board.flip(99))
        assertEquals(MemoryBoard.Flip.Ignored, board.flip(-1))
    }

    // --- Finishing -------------------------------------------------------------------------

    @Test
    fun `the board completes when every pair is found`() {
        val board = MemoryBoard.of(listOf("A", "B"), Random(5))
        listOf("A", "B").forEach { symbol ->
            val first = board.find(symbol)
            board.flip(first)
            board.flip(board.find(symbol, not = first))
        }
        assertTrue(board.isComplete)
        assertEquals(2, board.matchedPairs)
    }

    @Test
    fun `moves count attempted pairs, not taps`() {
        val board = MemoryBoard.of(listOf("A", "B"), Random(5))
        val a = board.find("A")
        board.flip(a)              // first of a pair - not yet a move
        assertEquals(0, board.moves)
        board.flip(board.find("B"))  // completes an attempt
        assertEquals(1, board.moves)
    }
}

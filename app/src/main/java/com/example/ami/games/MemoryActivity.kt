package com.example.ami.games

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.ami.R
import android.widget.ImageButton

/**
 * Memory Pairs.
 *
 * The pause after a mismatch is the whole ergonomics of this screen. [MemoryBoard] reports
 * the mismatch and leaves both cards showing; this waits [MISMATCH_PAUSE_MS] before turning
 * them back, which is noticeably longer than a typical matching game. Someone who needs a
 * second look to register what they turned over gets it, and the board refuses further
 * taps in the meantime so an impatient double-tap cannot hide the pair early.
 *
 * Content descriptions carry the symbol name, so TalkBack users get "showing sunflower"
 * rather than an unlabelled button - an emoji alone announces as nothing useful.
 */
class MemoryActivity : AppCompatActivity() {

    private lateinit var board: MemoryBoard
    private lateinit var grid: GridLayout
    private lateinit var scene: MemorySceneView

    /**
     * The shared hint line doubles as the progress readout here, so the scene chrome stays
     * identical across all five screens rather than this one growing a label of its own.
     */
    private lateinit var progressView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val cardViews = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)

        grid = findViewById(R.id.memoryGrid)
        scene = findViewById(R.id.memoryScene)
        progressView = findViewById(R.id.gameHint)

        findViewById<TextView>(R.id.gameHeadline).setText(R.string.game_memory_headline)
        findViewById<ImageButton>(R.id.btnGameClose).setOnClickListener { finish() }
        GameFinish.bind(this) { deal() }

        deal()
    }

    private fun deal() {
        handler.removeCallbacksAndMessages(null)
        GameFinish.hide(this)
        board = MemoryBoard.of()
        grid.removeAllViews()
        cardViews.clear()

        val inflater = LayoutInflater.from(this)
        board.cards.forEach { card ->
            val view = inflater.inflate(R.layout.item_memory_card, grid, false)
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(CARD_MARGIN_PX, CARD_MARGIN_PX, CARD_MARGIN_PX, CARD_MARGIN_PX)
            }
            view.layoutParams = params
            view.setOnClickListener { onCardTapped(card.index) }
            grid.addView(view)
            cardViews.add(view)
        }
        render()
    }

    private fun onCardTapped(index: Int) {
        when (board.flip(index)) {
            is MemoryBoard.Flip.Ignored -> return

            is MemoryBoard.Flip.Revealed -> render()

            is MemoryBoard.Flip.Matched -> {
                render()
                if (board.isComplete) {
                    progressView.setText(R.string.game_memory_done)
                    // Held back a moment so the final pair is seen turning over rather
                    // than being covered by the panel the instant it matches.
                    handler.postDelayed({
                        GameFinish.show(
                            this,
                            R.string.game_memory_done_title,
                            R.string.game_memory_done_body
                        )
                    }, FINISH_DELAY_MS)
                }
            }

            is MemoryBoard.Flip.Mismatched -> {
                render()
                handler.postDelayed({
                    board.hideMismatched()
                    render()
                }, MISMATCH_PAUSE_MS)
            }
        }
    }

    private fun render() {
        board.cards.forEachIndexed { index, card ->
            val view = cardViews.getOrNull(index) ?: return@forEachIndexed
            val label = view.findViewById<TextView>(R.id.memoryCardSymbol)

            if (card.faceUp || card.matched) {
                label.text = card.symbol
                view.setBackgroundResource(
                    if (card.matched) R.drawable.bg_memory_card_matched
                    else R.drawable.bg_memory_card_up
                )
                view.contentDescription =
                    getString(R.string.game_memory_card_face, index + 1, nameOf(card.symbol))
            } else {
                label.text = ""
                view.setBackgroundResource(R.drawable.bg_memory_card_down)
                view.contentDescription = getString(R.string.game_memory_card, index + 1)
            }

            // A matched card is no longer a control; stop offering it as one.
            view.isClickable = !card.matched
        }

        if (!board.isComplete) {
            progressView.text = getString(
                R.string.game_memory_progress,
                board.matchedPairs,
                board.totalPairs
            )
        }
    }

    /** Spoken names for the card faces; an emoji on its own announces as nothing. */
    private fun nameOf(symbol: String): String = when (symbol) {
        "🌻" -> "sunflower"
        "🍎" -> "apple"
        "🐦" -> "bird"
        "🌙" -> "moon"
        "🫖" -> "teapot"
        "⭐" -> "star"
        else -> "card"
    }

    override fun onResume() {
        super.onResume()
        scene.start()
    }

    override fun onPause() {
        super.onPause()
        scene.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        /**
         * Longer than a typical matching game on purpose - see the class comment.
         */
        const val MISMATCH_PAUSE_MS = 1_400L

        const val CARD_MARGIN_PX = 12

        /** Long enough to watch the last pair land before the panel appears. */
        const val FINISH_DELAY_MS = 900L
    }
}

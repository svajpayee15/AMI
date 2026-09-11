package com.example.ami.games

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.ami.R

/**
 * Bubble pop.
 *
 * The session is finite. It used to refill forever, which looked right and felt pointless:
 * the person could tap indefinitely and never arrive anywhere. Now a set of thoughts is
 * dealt out, the field visibly thins as they go, and clearing the last one ends the
 * exercise on the shared finish panel.
 *
 * Progress replaces the hint line only once something has been popped, and counts up
 * rather than down - "12 to go" on arrival reads as a quota, "3 gone" reads as progress.
 */
class BubbleActivity : AppCompatActivity() {

    private lateinit var view: BubbleView
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bubbles)

        view = findViewById(R.id.bubbleView)
        findViewById<TextView>(R.id.gameHeadline).setText(R.string.game_bubbles_headline)
        findViewById<TextView>(R.id.gameHint).setText(R.string.game_bubbles_hint_tap)
        findViewById<ImageButton>(R.id.btnGameClose).setOnClickListener { finish() }

        hint = findViewById(R.id.gameHint)
        view.onPopped = { cleared, total -> showProgress(cleared, total) }
        view.onCleared = {
            GameFinish.show(this, R.string.game_bubbles_done_title, R.string.game_bubbles_cleared)
        }
        GameFinish.bind(this) {
            view.reset()
        }
    }

    /**
     * The hint line becomes the progress line once the first bubble is popped.
     *
     * Counting up rather than down, and only after they have started: "12 to go" on
     * arrival reads as a quota, where "3 gone" reads as something achieved.
     */
    private fun showProgress(cleared: Int, total: Int) {
        hint.text = if (cleared == 0) {
            getString(R.string.game_bubbles_hint_tap)
        } else {
            getString(R.string.game_bubbles_progress, cleared, total)
        }
    }

    // The loop runs only while the screen is actually in front of someone.
    override fun onResume() {
        super.onResume()
        view.start()
    }

    override fun onPause() {
        super.onPause()
        view.stop()
    }
}

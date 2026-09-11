package com.example.ami.games

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.ami.R

/**
 * The hub for the four relaxation activities.
 *
 * They share one screen rather than four entries scattered around the app so that someone
 * who is anxious has a single place to go, and so that the framing - nothing is timed,
 * nothing is scored - is stated once, up front, where it sets expectations before anything
 * is opened.
 *
 * The four are deliberately different in what they ask: a rhythm to follow, something to
 * aim at, something to remember, and one that asks nothing at all. On a bad day only the
 * last of those is bearable, and it needs to be there.
 */
class GamesActivity : AppCompatActivity() {

    private enum class Game(
        val titleRes: Int,
        val bodyRes: Int,
        val iconRes: Int,
        val screen: Class<out Activity>
    ) {
        BREATHE(
            R.string.game_breathe_title,
            R.string.game_breathe_body,
            R.drawable.ic_wb_calm,
            BreathingActivity::class.java
        ),
        BUBBLES(
            R.string.game_bubbles_title,
            R.string.game_bubbles_body,
            R.drawable.ic_wb_thrive,
            BubbleActivity::class.java
        ),
        MEMORY(
            R.string.game_memory_title,
            R.string.game_memory_body,
            // Not the brain glyph, which renders as an info "(i)" and reads as "tap for
            // help" rather than as a matching game.
            R.drawable.ic_wb_connection,
            MemoryActivity::class.java
        ),
        POND(
            R.string.game_water_title,
            R.string.game_water_body,
            R.drawable.ic_wb_moon,
            PondActivity::class.java
        ),
        MUSIC(
            R.string.game_music_title,
            R.string.game_music_body,
            R.drawable.ic_speaker,
            MusicActivity::class.java
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_games)

        val container = findViewById<LinearLayout>(R.id.gamesList)
        val inflater = LayoutInflater.from(this)

        Game.entries.forEach { game ->
            val card = inflater.inflate(R.layout.item_game_card, container, false)
            card.findViewById<TextView>(R.id.gameTitle).setText(game.titleRes)
            card.findViewById<TextView>(R.id.gameBody).setText(game.bodyRes)
            card.findViewById<android.widget.ImageView>(R.id.gameIcon).setImageResource(game.iconRes)
            card.contentDescription = getString(game.titleRes)
            card.setOnClickListener { startActivity(Intent(this, game.screen)) }
            container.addView(card)
        }
    }
}

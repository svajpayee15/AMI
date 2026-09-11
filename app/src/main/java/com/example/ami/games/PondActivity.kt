package com.example.ami.games

import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.ami.R

/**
 * Nature touch.
 *
 * Note what is missing: there is no counter on screen. [PondView] reports touches and this
 * screen ignores them, on purpose - the moment a number appears, someone starts trying to
 * make it go up, and the one activity here that asks nothing of anybody would have
 * acquired a goal.
 */
class PondActivity : AppCompatActivity() {

    private lateinit var view: PondView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pond)

        view = findViewById(R.id.pondView)
        findViewById<TextView>(R.id.gameHeadline).setText(R.string.game_water_headline)
        findViewById<TextView>(R.id.gameHint).setText(R.string.game_water_hint)
        findViewById<ImageView>(R.id.gameHintIcon).setImageResource(R.drawable.ic_game_swipe)
        findViewById<ImageButton>(R.id.btnGameClose).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        view.start()
    }

    override fun onPause() {
        super.onPause()
        view.stop()
    }
}

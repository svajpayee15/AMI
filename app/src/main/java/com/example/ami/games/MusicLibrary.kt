package com.example.ami.games

import androidx.annotation.StringRes
import com.example.ami.R

/**
 * The moods on the music screen.
 *
 * Just the categories: the tracks themselves live in [MusicStore], because they are
 * whatever the person chose off their own phone rather than anything shipped with the app.
 * Each mood keeps its own list, so "Rain" and "Calm" can hold different music and
 * switching between them switches what plays.
 */
object MusicLibrary {

    enum class Mood(@StringRes val labelRes: Int) {
        CALM(R.string.game_music_mood_calm),
        NATURE(R.string.game_music_mood_nature),
        GAMMA(R.string.game_music_mood_gamma),
        RAIN(R.string.game_music_mood_rain)
    }
}

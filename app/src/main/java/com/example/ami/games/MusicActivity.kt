package com.example.ami.games

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ami.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Music therapy: pick a mood, add your own music, and the record turns while it plays.
 *
 * No audio came with the design hand-off, and bundling a stock library would have been the
 * wrong answer anyway - the music that settles an eighty-year-old is the music they
 * already know. So the design's own "ADD MUSIC +" is the feature: the system picker hands
 * back tracks already on the phone, [MusicStore] keeps them per mood with a persistable
 * read grant, and a plain [MediaPlayer] plays them.
 *
 * Playback is torn down in `onPause` rather than left running. A relaxation screen is not
 * a background music player, and a MediaPlayer that survives the screen is one the person
 * has no way to stop.
 */
class MusicActivity : AppCompatActivity() {

    private lateinit var scene: MusicSceneView
    private lateinit var titleView: TextView
    private lateinit var timeView: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var emptyNote: TextView
    private lateinit var playButton: ImageButton

    private lateinit var store: MusicStore

    private val chipViews = mutableMapOf<MusicLibrary.Mood, TextView>()
    private var mood = MusicLibrary.Mood.CALM

    private var player: MediaPlayer? = null
    private var tracks: List<MusicStore.Track> = emptyList()
    private var index = 0

    /**
     * Multiple selection, because someone setting this up once wants to add an album, not
     * repeat the whole flow per file.
     */
    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> onPicked(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music)

        store = MusicStore(this)
        scene = findViewById(R.id.musicScene)
        titleView = findViewById(R.id.musicTitle)
        timeView = findViewById(R.id.musicTime)
        progressView = findViewById(R.id.musicProgress)
        emptyNote = findViewById(R.id.musicEmptyNote)
        playButton = findViewById(R.id.btnMusicPlay)

        findViewById<TextView>(R.id.gameHeadline).setText(R.string.game_music_headline)
        // This screen carries its own hint above the player card; see activity_music.xml.
        findViewById<View>(R.id.gameHintRow).visibility = View.GONE
        findViewById<ImageButton>(R.id.btnGameClose).setOnClickListener { finish() }

        playButton.setOnClickListener { togglePlay() }
        findViewById<ImageButton>(R.id.btnMusicPrev).setOnClickListener { step(-1) }
        findViewById<ImageButton>(R.id.btnMusicNext).setOnClickListener { step(1) }
        findViewById<TextView>(R.id.btnMusicAdd).setOnClickListener { addMusic() }

        buildChips()
        loadTracks()
        observeProgress()
    }

    // --- Library -----------------------------------------------------------------------

    private fun addMusic() {
        try {
            picker.launch(arrayOf("audio/*"))
        } catch (e: Exception) {
            // Some stripped-down devices have no document picker at all.
            Log.w(TAG, "No document picker available", e)
            Toast.makeText(this, R.string.game_music_no_picker, Toast.LENGTH_LONG).show()
        }
    }

    private fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val added = store.add(mood, uris)
        loadTracks()
        val message = if (added > 0) {
            resources.getQuantityString(R.plurals.game_music_added, added, added)
        } else {
            getString(R.string.game_music_add_failed)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun loadTracks() {
        tracks = store.tracksFor(mood)
        index = index.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        render()
    }

    // --- Playback ----------------------------------------------------------------------

    private fun togglePlay() {
        if (tracks.isEmpty()) return
        val current = player
        if (current != null && current.isPlaying) {
            current.pause()
        } else if (current != null) {
            current.start()
        } else {
            play(index)
            return
        }
        render()
    }

    private fun step(direction: Int) {
        if (tracks.isEmpty()) return
        // Wraps in both directions, so neither end of the list is a dead button.
        index = ((index + direction) % tracks.size + tracks.size) % tracks.size
        play(index)
    }

    private fun play(at: Int) {
        release()
        val track = tracks.getOrNull(at) ?: return
        try {
            player = MediaPlayer().apply {
                setDataSource(this@MusicActivity, track.uri)
                setOnCompletionListener { step(1) }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "Playback failed ($what/$extra) for ${track.title}")
                    Toast.makeText(
                        this@MusicActivity,
                        R.string.game_music_play_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                    release()
                    render()
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            // A file that has been moved or deleted since it was added.
            Log.w(TAG, "Could not open ${track.title}", e)
            Toast.makeText(this, R.string.game_music_play_failed, Toast.LENGTH_SHORT).show()
            release()
        }
        render()
    }

    private fun release() {
        player?.runCatching {
            stop()
            release()
        }
        player = null
    }

    /** Drives the progress bar and the clock while something is playing. */
    private fun observeProgress() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val p = player
                    if (p != null && p.isPlaying) {
                        val duration = p.duration.coerceAtLeast(1)
                        progressView.progress = (p.currentPosition * 100 / duration)
                        timeView.text = format(p.currentPosition)
                    }
                    delay(TICK_MS)
                }
            }
        }
    }

    private fun format(ms: Int): String {
        val total = ms / 1000
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    // --- Chrome ------------------------------------------------------------------------

    private fun buildChips() {
        val container = findViewById<LinearLayout>(R.id.musicChipRows)
        container.removeAllViews()
        chipViews.clear()

        // Two per row: at this text size four across clips on a 360dp screen.
        MusicLibrary.Mood.entries.chunked(2).forEach { rowMoods ->
            val row = newRow()
            rowMoods.forEach { m ->
                val chip = newChip(getString(m.labelRes)) { select(m) }
                chipViews[m] = chip
                row.addView(chip)
            }
            container.addView(row)
        }
    }

    private fun newRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.space_sm) }
    }

    private fun newChip(label: String, onTap: () -> Unit) = TextView(this).apply {
        text = label
        setTextAppearance(R.style.AmiGameCardText)
        gravity = Gravity.CENTER
        minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
        val padH = resources.getDimensionPixelSize(R.dimen.space_md)
        setPadding(padH, 0, padH, 0)
        isClickable = true
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = resources.getDimensionPixelSize(R.dimen.space_sm) }
    }

    private fun select(next: MusicLibrary.Mood) {
        if (next == mood) return
        // Switching mood stops playback rather than carrying a track across into music
        // chosen for a different feeling.
        release()
        mood = next
        index = 0
        loadTracks()
    }

    private fun render() {
        chipViews.forEach { (m, view) ->
            val on = m == mood
            view.setBackgroundResource(if (on) R.drawable.bg_game_chip_on else R.drawable.bg_game_chip)
            view.setTextColor(if (on) getColor(R.color.game_cream) else getColor(R.color.game_ink))
        }

        val empty = tracks.isEmpty()
        val playing = player?.isPlaying == true

        titleView.text = if (empty) getString(R.string.game_music_empty) else tracks[index].title
        emptyNote.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            timeView.text = "--:--"
            progressView.progress = 0
        }

        // The controls stay visible but read as unavailable, rather than vanishing and
        // leaving a card that looks broken.
        val alpha = if (empty) 0.4f else 1f
        playButton.alpha = alpha
        findViewById<View>(R.id.btnMusicPrev).alpha = alpha
        findViewById<View>(R.id.btnMusicNext).alpha = alpha

        playButton.setImageResource(
            if (playing) R.drawable.ic_game_pause else R.drawable.ic_game_play
        )
        scene.spinning = playing
    }

    override fun onResume() {
        super.onResume()
        scene.start()
        render()
    }

    override fun onPause() {
        super.onPause()
        // Not a background player: leaving the screen stops the music, because there is
        // no notification or control left behind that could stop it afterwards.
        release()
        scene.spinning = false
        scene.stop()
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        release()
    }

    private companion object {
        const val TAG = "AMI_MUSIC"
        const val TICK_MS = 400L
    }
}

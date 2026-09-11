package com.example.ami.games

import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ami.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Paced breathing, spoken and drawn.
 *
 * Starts on its own when the screen opens. Someone who has come here because they feel
 * anxious should not have to make a decision first, and the reference design has no start
 * button either - the headline does the guiding and the close X is the only control.
 *
 * The screen is kept awake for the duration. A breathing exercise that dims out halfway
 * through, on a phone with a 30-second timeout, is worse than not offering one.
 *
 * The clock is [SystemClock.elapsedRealtime], not wall time: it does not jump when the
 * network corrects the device clock, which would otherwise skip the exercise forward
 * mid-breath.
 *
 * Cues are spoken on phase *changes* only, tracked against the phase last announced.
 * Speaking every frame would stutter, and speaking on a timer would drift out of step with
 * the dandelion - both of which turn following along into work.
 */
class BreathingActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var view: BreathingView
    private lateinit var headline: TextView

    private val session = BreathingSession()

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var startedAt = 0L
    private var running = false
    private var lastSpokenPhase: BreathPhase? = null
    private var finished = false

    /**
     * How far in the exercise had got when the screen was last left.
     *
     * Without this, leaving for a moment and coming back restarted the whole thing from
     * the first breath - two minutes of someone's effort thrown away because they glanced
     * at a notification. Resuming picks up where they were.
     */
    private var accumulatedMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_breathing)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        view = findViewById(R.id.breathingView)
        view.session = session
        headline = findViewById(R.id.gameHeadline)
        headline.setText(R.string.game_breathe_h_inhale)

        // The dandelion is the instruction here, so the hint row would only repeat it.
        findViewById<View>(R.id.gameHintRow).visibility = View.GONE
        findViewById<ImageButton>(R.id.btnGameClose).setOnClickListener { finish() }

        GameFinish.bind(this) { restart() }

        tts = TextToSpeech(this, this)
        observeClock()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            // Slower than normal speech. These are cues to breathe along with, not
            // instructions to get through.
            tts?.setSpeechRate(0.85f)
            ttsReady = true
        }
    }

    private fun observeClock() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (running) tick()
                    delay(TICK_MS)
                }
            }
        }
    }

    private fun tick() {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        view.elapsedMs = elapsed

        val moment = session.momentAt(elapsed)
        if (moment.isComplete) {
            finishSession()
            return
        }
        if (moment.phase != lastSpokenPhase) {
            lastSpokenPhase = moment.phase
            speak(moment.phase.spoken)
            headline.setText(headlineFor(moment))
        }
    }

    /**
     * The reference changes the line with the phase, and says something different again on
     * the closing rest - which is what makes the screen feel like it is talking to you
     * rather than looping.
     */
    private fun headlineFor(moment: BreathingSession.Moment): Int = when (moment.phase) {
        BreathPhase.INHALE -> R.string.game_breathe_h_inhale
        BreathPhase.HOLD -> R.string.game_breathe_h_hold
        BreathPhase.EXHALE -> R.string.game_breathe_h_exhale
        BreathPhase.REST -> R.string.game_breathe_h_rest
    }

    /** Picks up from [accumulatedMs] rather than from zero. */
    private fun resumeSession() {
        if (finished) return
        running = true
        startedAt = SystemClock.elapsedRealtime() - accumulatedMs
        // Cleared so the cue for the phase they are resuming into is spoken again; coming
        // back to a silent screen mid-breath leaves them with no idea where they are.
        lastSpokenPhase = null
        view.elapsedMs = accumulatedMs
        view.started = true
    }

    private fun restart() {
        finished = false
        accumulatedMs = 0L
        resumeSession()
    }

    private fun finishSession() {
        running = false
        finished = true
        accumulatedMs = session.totalMs
        view.elapsedMs = session.totalMs
        headline.setText(R.string.game_breathe_h_done)
        speak(getString(R.string.game_breathe_h_done))
        GameFinish.show(this, R.string.game_breathe_done_title, R.string.game_breathe_done_body)
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AMI_BREATHE")
    }

    override fun onResume() {
        super.onResume()
        view.start()
        // Not while the finish panel is up: that would start a new exercise behind it.
        if (!finished && !GameFinish.isShowing(this)) resumeSession()
    }

    override fun onPause() {
        super.onPause()
        // Bank the progress before stopping, so returning continues rather than restarts.
        if (running) accumulatedMs = SystemClock.elapsedRealtime() - startedAt
        running = false
        view.stop()
        tts?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        view.stop()
        tts?.stop()
        tts?.shutdown()
    }

    private companion object {
        /** Fine enough that a phase change is announced without a perceptible lag. */
        const val TICK_MS = 60L
    }
}

package com.example.ami.games

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/**
 * A view that redraws itself continuously while it is running.
 *
 * Shared by the bubble field and the pond, which both need the same thing: a frame, a
 * delta since the last one, and the loop stopping dead when the screen goes away. Driving
 * the loop from `postInvalidateOnAnimation` rather than a timer keeps it on the display's
 * own cadence, and lets it stop cleanly - an animation that keeps ticking after
 * `onPause` is a background battery drain nobody attributes to a relaxation game.
 *
 * The delta is clamped at [MAX_DELTA_MS]. Without it, coming back after the screen has
 * been off hands the game a delta of several minutes: the bubbles would teleport off the
 * top and every ripple would expire in one frame.
 */
abstract class AnimatedGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var lastFrameNs = 0L

    var isRunning = false
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        // Forget the old timestamp so the first frame after a resume has a zero delta
        // rather than however long the screen was off.
        lastFrameNs = 0L
        postInvalidateOnAnimation()
    }

    fun stop() {
        isRunning = false
    }

    final override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = System.nanoTime()
        val deltaMs = if (lastFrameNs == 0L) {
            0L
        } else {
            ((now - lastFrameNs) / 1_000_000L).coerceIn(0L, MAX_DELTA_MS)
        }
        lastFrameNs = now

        if (isRunning) onFrame(deltaMs)
        drawFrame(canvas)

        if (isRunning) postInvalidateOnAnimation()
    }

    /** Advance the model. Not called while stopped, so a paused game stays put. */
    protected abstract fun onFrame(deltaMs: Long)

    protected abstract fun drawFrame(canvas: Canvas)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    companion object {
        /** Roughly three frames at 60Hz. */
        private const val MAX_DELTA_MS = 50L
    }
}

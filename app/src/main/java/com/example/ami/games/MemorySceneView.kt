package com.example.ami.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import com.example.ami.R
import kotlin.math.sin

/**
 * The backdrop behind Memory pairs: a soft sky with a few drifting motes.
 *
 * Quieter than the other three on purpose. This is the only scene with something to
 * concentrate on laid over the top, and a busy background competing with twelve cards
 * would make the game harder rather than calmer.
 */
class MemorySceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SceneView(context, attrs) {

    override val topColorRes = R.color.game_memory_top
    override val bottomColorRes = R.color.game_memory_bottom

    private val motePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private var sceneTimeMs = 0L

    override fun onFrame(deltaMs: Long) {
        sceneTimeMs += deltaMs
    }

    override fun drawScene(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = sceneTimeMs / 1000f
        val r = kotlin.random.Random(5)

        for (i in 0 until MOTES) {
            val baseX = r.nextFloat() * w
            val baseY = r.nextFloat() * h
            val size = w * (0.006f + r.nextFloat() * 0.012f)
            val phase = r.nextFloat() * 6.28f
            // Drift is a function of absolute time, so pausing and returning does not
            // leave the motes jumped to a new place.
            val x = baseX + sin(t * 0.18f + phase) * w * 0.03f
            val y = (baseY - t * 6f) .mod(h)
            canvas.drawCircle(x, y, size, motePaint)
        }
    }

    private companion object {
        const val MOTES = 26
    }
}

package com.example.ami.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import com.example.ami.R
import kotlin.math.min

/**
 * The music room: a warm interior with a record turning on the table.
 *
 * The record is the only moving part and it turns only while something is playing, so the
 * screen answers "is this on?" without anyone having to find and read the transport
 * controls. With no tracks loaded it sits still, which is the honest state.
 */
class MusicSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SceneView(context, attrs) {

    override val topColorRes = R.color.game_room_top
    override val bottomColorRes = R.color.game_room_bottom

    /** Set by the activity; the platter turns only when true. */
    var spinning: Boolean = false

    private var angle = 0f

    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x22FFFFFF
        strokeWidth = 2f
    }
    private val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSceneSized(w: Float, h: Float) {
        vignettePaint.shader = RadialGradient(
            w * 0.5f, h * 0.42f, min(w, h) * 0.95f,
            0x00000000, 0x66000000, Shader.TileMode.CLAMP
        )
    }

    override fun onFrame(deltaMs: Long) {
        // 33 rpm is about 198 degrees a second; slowed well below that so it reads as
        // calm rather than as a loading spinner.
        if (spinning) angle = (angle + deltaMs * 0.028f) % 360f
    }

    override fun drawScene(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // A table across the lower third, so the record has something to sit on.
        tablePaint.color = 0xFF3A2A22.toInt()
        canvas.drawRect(0f, h * 0.615f, w, h, tablePaint)
        tablePaint.color = 0x1AFFFFFF
        canvas.drawRect(0f, h * 0.615f, w, h * 0.63f, tablePaint)

        val cx = w * 0.5f
        val cy = h * 0.50f
        val radius = min(w, h) * 0.25f

        canvas.save()
        canvas.rotate(angle, cx, cy)

        discPaint.color = 0xFF14100E.toInt()
        canvas.drawCircle(cx, cy, radius, discPaint)
        for (i in 3..9) {
            canvas.drawCircle(cx, cy, radius * i / 10f, groovePaint)
        }
        discPaint.color = 0xFFD98F3F.toInt()
        canvas.drawCircle(cx, cy, radius * 0.3f, discPaint)
        discPaint.color = 0xFF14100E.toInt()
        canvas.drawCircle(cx, cy, radius * 0.045f, discPaint)

        // One highlight that turns with the platter, so the rotation is visible even
        // though a plain black disc would look identical at every angle.
        discPaint.color = 0x33FFFFFF
        canvas.drawCircle(cx + radius * 0.55f, cy - radius * 0.45f, radius * 0.09f, discPaint)
        canvas.restore()

        canvas.drawRect(0f, 0f, w, h, vignettePaint)
    }
}

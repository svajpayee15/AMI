package com.example.ami.games

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * Base for the four relaxation scenes: a full-bleed painted background with the design's
 * cut-out layers composited on top.
 *
 * The backgrounds are painted rather than shipped as images. The reference pack's
 * backdrops arrived as screenshots and watermarked comps - phone status bars and a
 * Shutterstock mark baked into the pixels - which could neither be shipped nor scaled to
 * an arbitrary screen without looking wrong. A two-stop gradient plus a few painted
 * passes reproduces the look, costs nothing in APK size, and fits any aspect ratio. The
 * isolated layers that *were* clean - the dandelion, the seed, the soap bubble, the cloud
 * - are real assets and are drawn from `drawable-nodpi`.
 *
 * Bitmaps are decoded once and shared through [layer], because each scene draws the same
 * sprite many times a frame at different sizes.
 */
abstract class SceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AnimatedGameView(context, attrs) {

    /**
     * The two gradient stops for this scene, as colour resources.
     *
     * Abstract properties with no backing field cannot carry @ColorRes, so the contract
     * lives here in the comment: both must be colours, not arbitrary ints.
     */
    protected abstract val topColorRes: Int

    protected abstract val bottomColorRes: Int

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val cache = mutableMapOf<Int, Bitmap>()

    /** Decoded once per view, at full size; callers scale with a destination rect. */
    protected fun layer(@DrawableRes id: Int): Bitmap =
        cache.getOrPut(id) { BitmapFactory.decodeResource(resources, id) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            ContextCompat.getColor(context, topColorRes),
            ContextCompat.getColor(context, bottomColorRes),
            Shader.TileMode.CLAMP
        )
        onSceneSized(w.toFloat(), h.toFloat())
    }

    /** Subclass hook for anything that has to be laid out against the new size. */
    protected open fun onSceneSized(w: Float, h: Float) = Unit

    final override fun drawFrame(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), skyPaint)
        drawScene(canvas)
    }

    protected abstract fun drawScene(canvas: Canvas)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cache.values.forEach { if (!it.isRecycled) it.recycle() }
        cache.clear()
    }
}

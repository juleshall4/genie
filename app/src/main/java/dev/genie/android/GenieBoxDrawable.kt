package dev.genie.android

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.sin

/**
 * The Genie box from the Paper design, for both the pill and the response panel. It's black with a
 * soft shade around it, a blurred halo and a thin light ring that catches the light at two corners.
 * The light slowly circles the box and the halo breathes. The shade and halo draw outside the
 * bounds, so the box's parent must not clip its children.
 */
class GenieBoxDrawable(private val density: Float) : Drawable() {
    var cornerRadius = 28 * density
        set(value) { field = value; invalidateSelf() }

    /** Where the ring's brightest light starts, in degrees clockwise from the top. */
    var lightAngle = 0f
        set(value) { field = value; invalidateSelf() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = (0.7f * 255).toInt()
        maskFilter = blur(22f)
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        alpha = (0.32f * 255).toInt()
        maskFilter = blur(14f)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = (0.5f * 255).toInt() }
    private val innerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0x0F, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 12 * density
        maskFilter = blur(6f)
    }
    private val rect = RectF()
    private val clip = Path()
    private val matrix = Matrix()

    override fun draw(canvas: Canvas) {
        val r = cornerRadius
        rect.set(bounds)
        val seconds = SystemClock.uptimeMillis() / 1000f
        turnLight(lightAngle + seconds * 360 / LIGHT_TURN_SECONDS)
        halo.alpha = (255 * 0.32f * (0.85f + 0.2f * sin(seconds * 2 * PI.toFloat() / 3.2f))).toInt()
        drawAround(canvas, 28 * density, 60 * density, shade)
        drawAround(canvas, 8 * density, 40 * density, halo)
        drawAround(canvas, 1.5f * density, r + 1.5f * density, ring)
        rect.set(bounds)
        canvas.drawRoundRect(rect, r, r, fill)
        // A faint light just inside the edge, like an inset shadow.
        clip.rewind()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRoundRect(rect, r, r, innerGlow)
        canvas.restore()
        invalidateSelf()
    }

    private fun drawAround(canvas: Canvas, spread: Float, radius: Float, paint: Paint) {
        rect.set(bounds)
        rect.inset(-spread, -spread)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        halo.shader = light(HALO)
        ring.shader = light(RING)
    }

    // A conic gradient around the centre.
    private fun light(alphas: IntArray) = SweepGradient(bounds.exactCenterX(), bounds.exactCenterY(),
        alphas.map { Color.argb(it, 255, 255, 255) }.toIntArray(), STOPS)

    // Android's sweep starts at three o'clock, CSS at twelve.
    private fun turnLight(degrees: Float) {
        matrix.setRotate(degrees - 90, bounds.exactCenterX(), bounds.exactCenterY())
        halo.shader?.setLocalMatrix(matrix)
        ring.shader?.setLocalMatrix(matrix)
    }

    // CSS blur() takes a standard deviation; BlurMaskFilter takes a radius.
    private fun blur(sigmaDp: Float) = BlurMaskFilter((sigmaDp * density - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL)

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val LIGHT_TURN_SECONDS = 8f
        val STOPS = floatArrayOf(0f, 0.12f, 0.25f, 0.38f, 0.5f, 0.62f, 0.75f, 0.88f, 1f)
        val HALO = intArrayOf(0x59, 0xE6, 0x59, 0x26, 0x59, 0xE6, 0x59, 0x26, 0x59)
        val RING = intArrayOf(0x66, 0xFF, 0x66, 0x33, 0x66, 0xFF, 0x66, 0x33, 0x66)
    }
}

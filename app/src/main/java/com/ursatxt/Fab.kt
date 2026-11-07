package com.ursatxt

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** A superellipse, |x|^4 + |y|^4 = 1, filling [w] by [h] from (x, y). */
fun squirclePath(x: Float, y: Float, w: Float, h: Float, p: Path = Path()): Path {
    p.reset()
    val n = 4.0
    val steps = 96
    for (i in 0..steps) {
        val t = 2 * Math.PI * i / steps
        val c = cos(t); val s = sin(t)
        val px = x + w / 2 + (w / 2) * (sign(c) * abs(c).pow(2 / n)).toFloat()
        val py = y + h / 2 + (h / 2) * (sign(s) * abs(s).pow(2 / n)).toFloat()
        if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
    }
    p.close()
    return p
}

/** A squircle, filled with [fill] or stroked [strokeDp] wide in [stroke]. */
class Squircle(private val c: Context, var fill: Int, var stroke: Int = 0, private val strokeDp: Float = 2f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun draw(cv: Canvas) {
        val b = bounds
        val sw = if (stroke != 0) c.dp(strokeDp) else 0f
        squirclePath(b.left + sw / 2, b.top + sw / 2, b.width() - sw, b.height() - sw, path)
        if (fill != 0) { paint.style = Paint.Style.FILL; paint.color = fill; cv.drawPath(path, paint) }
        if (stroke != 0) { paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; paint.color = stroke; cv.drawPath(path, paint) }
    }
    override fun getOutline(o: android.graphics.Outline) {
        val b = bounds
        if (android.os.Build.VERSION.SDK_INT >= 30) o.setPath(squirclePath(b.left.toFloat(), b.top.toFloat(), b.width().toFloat(), b.height().toFloat()))
        else o.setRoundRect(b, b.width() * 0.3f)
    }
    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { paint.colorFilter = f }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

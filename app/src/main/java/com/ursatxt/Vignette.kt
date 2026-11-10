package com.ursatxt

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.os.Build
import android.view.View

/**
 * A soft edge where a list scrolls under a sticky block or off the screen.
 * Dark mode: black in Multiply, drawn straight into the parent's canvas, so it darkens
 * what scrolls beneath rather than painting over it. Light mode: a fade to the
 * background colour, since Multiply can only darken. During a theme change the two
 * cross-blend with [Theme.t]. [edge] picks the side it hangs from; [fill] is the colour
 * rows dissolve into. Fades in and out with [show] over 250 ms.
 */
class Vignette(ctx: Context, private val edge: Int, private val fill: () -> Int = { Theme.bg() }) : View(ctx) {

    constructor(ctx: Context, fromBottom: Boolean) : this(ctx, if (fromBottom) BOTTOM else TOP)

    private val fromBottom = edge == BOTTOM
    private val across = edge == START || edge == END

    private val shade = Paint().apply {
        if (Build.VERSION.SDK_INT >= 29) blendMode = BlendMode.MULTIPLY
        else xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }
    private val wash = Paint()
    private var washColor = 0
    private var washT = -1f
    private var on = false

    init { alpha = 0f }

    // A partly faded view normally draws into its own layer, and Multiply would then
    // blend against that empty layer instead of the list.
    override fun hasOverlappingRendering() = false

    fun show(v: Boolean) {
        if (v == on) return
        on = v
        animate().alpha(if (v) 1f else 0f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
    }

    /** The gradient's line, from the edge (full) inwards: x0, y0, x1, y1. */
    private fun ends(): FloatArray {
        val w = width.toFloat(); val h = height.toFloat()
        return when (edge) {
            BOTTOM -> floatArrayOf(0f, h, 0f, 0f)
            START -> floatArrayOf(0f, 0f, w, 0f)
            END -> floatArrayOf(w, 0f, 0f, 0f)
            else -> floatArrayOf(0f, 0f, 0f, h)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val e = ends()
        shade.shader = LinearGradient(e[0], e[1], e[2], e[3], 0x59000000, 0x00000000, Shader.TileMode.CLAMP)  // 35% black
        washColor = 0
    }

    override fun onDraw(c: Canvas) {
        val t = Theme.t
        val w = width.toFloat(); val h = height.toFloat()
        if (t < 1f) {
            shade.alpha = (255 * (1 - t)).toInt()
            c.drawRect(0f, 0f, w, h, shade)
        }
        // Over the shade, the background colour at full strength at the edge, so rows
        // dissolve into the sticky block or the screen edge instead of being cut there.
        // Dark mode keeps it to the outer half, leaving the shade to do the rest.
        val bg = fill()
        if (bg != washColor || t != washT) {
            washColor = bg; washT = t
            val e = ends()
            // The sticky block cuts rows across their text, so the top fades longer and deeper;
            // at the bottom the rows run on under the gesture bar.
            val dark = if (fromBottom || across) DARK else TOP_DARK
            val light = if (fromBottom || across) LIGHT else TOP_LIGHT
            val colors = IntArray(STOPS.size) { k ->
                val a = dark[k] + (light[k] - dark[k]) * t
                (bg and 0xFFFFFF) or ((255 * a).toInt() shl 24)
            }
            wash.shader = LinearGradient(e[0], e[1], e[2], e[3], colors, STOPS, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, wash)
    }

    companion object {
        const val TOP = 0
        const val BOTTOM = 1
        const val START = 2
        const val END = 3

        // Background opacity from the edge (0) to the inner end (1).
        private val STOPS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        private val DARK = floatArrayOf(1f, 0.45f, 0.12f, 0f, 0f)
        private val LIGHT = floatArrayOf(1f, 0.62f, 0.32f, 0.1f, 0f)
        private val TOP_DARK = floatArrayOf(1f, 0.8f, 0.45f, 0.15f, 0f)
        private val TOP_LIGHT = floatArrayOf(1f, 0.85f, 0.55f, 0.22f, 0f)
    }
}

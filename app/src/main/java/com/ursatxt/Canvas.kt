package com.ursatxt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A sketch: ink as alpha in tiles of [TILE] px, so no bitmap passes a GPU's texture limit
 * (a 2160 x 4800 canvas would). One finger draws; two pinch and pan.
 *
 * THE BRUSH IS A CHALK PASTEL, built like Sketchbook's pastels: a stroke paints solid
 * dabs into a mask, and the mask reaches the paper through a grain fixed to the canvas,
 * so a stroke's edge breaks up and a second stroke over it fills the gaps. Sketchbook's
 * downloadable sets have no "Chalk Pastel"; its "Soft Pastel" spaces dabs at 0.3 of the
 * radius over an inverted paper texture, and this follows that.
 */
class SketchCanvas(c: Context, val w: Int, val h: Int) : View(c) {
    companion object {
        const val TILE = 512
        /** Stroke widths at sizes 1 to 5, in dp across, seen at 50% zoom. */
        val SIZES_DP = floatArrayOf(3f, 5f, 8f, 12f, 18f)
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 2f
        private const val GRAIN = 256
    }

    private val cols = (w + TILE - 1) / TILE
    private val rows = (h + TILE - 1) / TILE
    private var ink = arrayOfNulls<Bitmap>(cols * rows)
    private var base = arrayOfNulls<Bitmap>(cols * rows)
    private val live = arrayOfNulls<Bitmap>(cols * rows)
    private val liveTouched = HashSet<Int>()

    /** One undoable action: a stroke's dabs (x, y, radius), or a wipe. */
    private class Action(val dabs: FloatArray?, val n: Int)
    private val history = ArrayList<Action>()
    private var cursor = 0
    val canUndo get() = cursor > 0
    val canRedo get() = cursor < history.size
    var onChange: () -> Unit = {}

    var size = 3
    var inverted = false
        set(v) { field = v; paintInk(); invalidate() }

    // ---- the grain ----

    private val grain: Bitmap = Bitmap.createBitmap(GRAIN, GRAIN, Bitmap.Config.ARGB_8888).also { bm ->
        // Value noise in two octaves, tiling, then a curve that leaves about a quarter of
        // the paper's tooth bare under one stroke.
        val rnd = java.util.Random(7)
        fun layer(cell: Int): FloatArray {
            val n = GRAIN / cell
            val g = FloatArray(n * n) { rnd.nextFloat() }
            val out = FloatArray(GRAIN * GRAIN)
            for (y in 0 until GRAIN) for (x in 0 until GRAIN) {
                val fx = x.toFloat() / cell; val fy = y.toFloat() / cell
                val x0 = fx.toInt() % n; val y0 = fy.toInt() % n
                val x1 = (x0 + 1) % n; val y1 = (y0 + 1) % n
                val tx = fx - fx.toInt(); val ty = fy - fy.toInt()
                val sx = tx * tx * (3 - 2 * tx); val sy = ty * ty * (3 - 2 * ty)
                val a = g[y0 * n + x0] + (g[y0 * n + x1] - g[y0 * n + x0]) * sx
                val b = g[y1 * n + x0] + (g[y1 * n + x1] - g[y1 * n + x0]) * sx
                out[y * GRAIN + x] = a + (b - a) * sy
            }
            return out
        }
        val fine = layer(4); val mid = layer(16); val coarse = layer(32)
        val px = IntArray(GRAIN * GRAIN)
        for (i in px.indices) {
            val v = 0.5f * fine[i] + 0.3f * mid[i] + 0.2f * coarse[i]
            px[i] = ((255 * (0.35f + 0.65f * v)).toInt() shl 24)
        }
        bm.setPixels(px, 0, GRAIN, 0, 0, GRAIN, GRAIN)
    }
    private val grainShader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    private val grainMatrix = Matrix()

    /**
     * Where the stroke is full, the grain barely shows; where it thins toward its edge,
     * only the paper's high points take chalk: alpha' = 3.2 alpha - 0.85, on mask x grain.
     */
    private fun chalk(rgb: Int) = android.graphics.ColorMatrixColorFilter(floatArrayOf(
        0f, 0f, 0f, 0f, Color.red(rgb).toFloat(),
        0f, 0f, 0f, 0f, Color.green(rgb).toFloat(),
        0f, 0f, 0f, 0f, Color.blue(rgb).toFloat(),
        0f, 0f, 0f, 3.2f, -0.85f * 255,
    ))
    private val commitPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = grainShader; colorFilter = chalk(Color.BLACK) }

    /** A soft dab: full to 25% of its radius, then falling to nothing at the rim. */
    private val dabMark: Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ALPHA_8).also { bm ->
        val b = ByteArray(64 * 64)
        for (y in 0 until 64) for (x in 0 until 64) {
            val d = hypot(x + 0.5f - 32f, y + 0.5f - 32f) / 32f
            val t = ((1f - d) / 0.75f).coerceIn(0f, 1f)
            b[y * 64 + x] = (255 * t * t * (3 - 2 * t)).toInt().toByte()
        }
        bm.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(b))
    }
    private val dabPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dabRect = android.graphics.RectF()

    // ---- view state ----

    var zoom = MIN_ZOOM; private set
    private var tx = 0f
    private var ty = 0f
    private val inkPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val livePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = grainShader }
    private val paper = Paint()
    var backdrop = 0xFF2A2A2A.toInt()
        set(v) { field = v; invalidate() }

    private fun paintInk() {
        val c = if (inverted) Color.WHITE else Color.BLACK
        inkPaint.colorFilter = PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN)
        livePaint.colorFilter = chalk(c)
        paper.color = if (inverted) Color.BLACK else Color.WHITE
    }

    init { paintInk() }

    private fun clampView() {
        val cw = w * zoom; val ch = h * zoom
        tx = if (cw <= width) (width - cw) / 2 else tx.coerceIn(width - cw, 0f)
        ty = if (ch <= height) (height - ch) / 2 else ty.coerceIn(height - ch, 0f)
    }

    private var placed = false
    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        if (!placed) { tx = 0f; ty = 0f; placed = true }
        clampView()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(backdrop)
        c.save()
        c.translate(tx, ty)
        c.scale(zoom, zoom)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paper)
        // Only the tiles on screen.
        val x0 = max(0, (-tx / zoom / TILE).toInt()); val x1 = min(cols - 1, ((width - tx) / zoom / TILE).toInt())
        val y0 = max(0, (-ty / zoom / TILE).toInt()); val y1 = min(rows - 1, ((height - ty) / zoom / TILE).toInt())
        for (ry in y0..y1) for (rx in x0..x1) {
            val k = ry * cols + rx
            val left = (rx * TILE).toFloat(); val top = (ry * TILE).toFloat()
            ink[k]?.let { c.drawBitmap(it, left, top, inkPaint) }
            // The grain sits in canvas space here, as it does when the stroke is committed.
            live[k]?.let {
                if (k in liveTouched) {
                    grainShader.setLocalMatrix(null)
                    c.drawBitmap(it, left, top, livePaint)
                }
            }
        }
        c.restore()
    }

    // ---- painting ----

    private fun tile(arr: Array<Bitmap?>, k: Int): Bitmap = arr[k] ?: Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ALPHA_8).also { arr[k] = it }

    private val dabCanvas = Canvas()

    /** One solid dab into the stroke's mask, in every tile it touches. */
    private fun dab(x: Float, y: Float, r: Float) {
        val a = max(0, ((x - r) / TILE).toInt()); val b = min(cols - 1, ((x + r) / TILE).toInt())
        val p = max(0, ((y - r) / TILE).toInt()); val q = min(rows - 1, ((y + r) / TILE).toInt())
        for (ry in p..q) for (rx in a..b) {
            val k = ry * cols + rx
            dabCanvas.setBitmap(tile(live, k))
            val cx = x - rx * TILE; val cy = y - ry * TILE
            dabRect.set(cx - r, cy - r, cx + r, cy + r)
            dabCanvas.drawBitmap(dabMark, null, dabRect, dabPaint)
            liveTouched += k
        }
        dabCanvas.setBitmap(null)
    }

    /** The stroke's mask into the ink, through the grain; the mask is cleared. */
    private fun commit() {
        for (k in liveTouched) {
            val m = live[k] ?: continue
            val left = (k % cols) * TILE.toFloat(); val top = (k / cols) * TILE.toFloat()
            grainMatrix.setTranslate(-left, -top)
            grainShader.setLocalMatrix(grainMatrix)
            dabCanvas.setBitmap(tile(ink, k))
            dabCanvas.drawBitmap(m, 0f, 0f, commitPaint)
            m.eraseColor(Color.TRANSPARENT)
        }
        dabCanvas.setBitmap(null)
        liveTouched.clear()
    }

    private fun clearLive() {
        for (k in liveTouched) live[k]?.eraseColor(Color.TRANSPARENT)
        liveTouched.clear()
    }

    /** Lays dabs from the last point to (x, y), 0.3 of the radius apart. */
    private var lx = 0f
    private var ly = 0f
    private var lr = 0f
    private var dabs = FloatArray(3 * 256)
    private var nDabs = 0

    private fun addDab(x: Float, y: Float, r: Float) {
        if (nDabs * 3 + 3 > dabs.size) dabs = dabs.copyOf(dabs.size * 2)
        dabs[nDabs * 3] = x; dabs[nDabs * 3 + 1] = y; dabs[nDabs * 3 + 2] = r
        nDabs++
        dab(x, y, r)
    }

    /** A stroke starts at 30% of its width and reaches full width over five radii. */
    private var run = 0f
    private fun taper() = min(1f, 0.3f + 0.7f * run / (5f * radius()))

    private fun lineTo(x: Float, y: Float, r: Float) {
        val d = hypot(x - lx, y - ly)
        val step = max(0.5f, 0.3f * min(r, lr))
        var t = step
        while (t <= d) {
            val f = t / d
            run += step
            addDab(lx + (x - lx) * f, ly + (y - ly) * f, (lr + (r - lr) * f) * taper())
            t += step
        }
        if (t - step < d) { lx += (x - lx) * ((t - step) / d); ly += (y - ly) * ((t - step) / d); lr += (r - lr) * ((t - step) / d) }
        else { lx = x; ly = y; lr = r }
    }

    /** Full radius at this size, in canvas px. */
    private fun radius(): Float = SIZES_DP[size - 1] * resources.displayMetrics.density * 2f / 2f

    // ---- history ----

    private fun replay() {
        ink = arrayOfNulls(cols * rows)
        for (k in base.indices) base[k]?.let { ink[k] = it.copy(Bitmap.Config.ALPHA_8, true) }
        for (i in 0 until cursor) {
            val a = history[i]
            val d = a.dabs
            if (d == null) { ink = arrayOfNulls(cols * rows); continue }
            for (j in 0 until a.n) dab(d[j * 3], d[j * 3 + 1], d[j * 3 + 2])
            commit()
        }
        invalidate()
    }

    private fun push(a: Action) {
        while (history.size > cursor) history.removeAt(history.size - 1)
        history += a
        cursor = history.size
        onChange()
    }

    fun undo() { if (canUndo) { cursor--; replay(); onChange() } }
    fun redo() { if (canRedo) { cursor++; replay(); onChange() } }

    fun wipe() {
        if (ink.all { it == null }) return
        ink = arrayOfNulls(cols * rows)
        push(Action(null, 0))
        invalidate()
    }

    /** The picture as opened: its ink becomes the floor undo stops at. */
    fun load(pic: Bitmap) {
        val row = IntArray(TILE)
        for (ry in 0 until rows) for (rx in 0 until cols) {
            var t: Bitmap? = null
            val bw = min(TILE, pic.width - rx * TILE); val bh = min(TILE, pic.height - ry * TILE)
            if (bw <= 0 || bh <= 0) continue
            val px = ByteArray(TILE * TILE)
            var any = false
            for (y in 0 until bh) {
                pic.getPixels(row, 0, bw, rx * TILE, ry * TILE + y, bw, 1)
                for (x in 0 until bw) {
                    val c = row[x]
                    val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                    val a = if (inverted) lum else 255 - lum
                    if (a > 2) { px[y * TILE + x] = a.toByte(); any = true }
                }
            }
            if (any) {
                t = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ALPHA_8)
                t.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
            }
            base[ry * cols + rx] = t
            ink[ry * cols + rx] = t?.copy(Bitmap.Config.ALPHA_8, true)
        }
        invalidate()
    }

    val empty get() = ink.all { it == null }

    /** The picture as it looks, for saving: paper and ink, opaque. */
    fun snapshot(): Array<Bitmap?> = Array(ink.size) { ink[it]?.copy(Bitmap.Config.ALPHA_8, false) }

    fun render(tiles: Array<Bitmap?>, inv: Boolean): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        c.drawColor(if (inv) Color.BLACK else Color.WHITE)
        val p = Paint().apply { colorFilter = PorterDuffColorFilter(if (inv) Color.WHITE else Color.BLACK, PorterDuff.Mode.SRC_IN) }
        for (k in tiles.indices) tiles[k]?.let { c.drawBitmap(it, (k % cols) * TILE.toFloat(), (k / cols) * TILE.toFloat(), p) }
        return out
    }

    // ---- touch ----

    private var drawing = false
    private var gesture = false
    private var lastT = 0L
    private var speedF = 1f
    private var pinchD = 0f
    private var pinchZ = 0f
    private var fx = 0f
    private var fy = 0f

    private fun toCanvasX(x: Float) = (x - tx) / zoom
    private fun toCanvasY(y: Float) = (y - ty) / zoom

    /**
     * How full the line is at this point. A stylus that reports pressure sets it; a finger
     * gives none, so a fast stroke thins the line, as Krita does, smoothed so it swells
     * and narrows rather than jumps.
     */
    private fun widthAt(e: MotionEvent, i: Int, h: Int): Float {
        val stylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        if (stylus) {
            val p = if (h >= 0) e.getHistoricalPressure(0, h) else e.getPressure(0)
            return (0.3f + 0.9f * p).coerceIn(0.3f, 1.2f)
        }
        val x = if (h >= 0) e.getHistoricalX(0, h) else e.getX(0)
        val y = if (h >= 0) e.getHistoricalY(0, h) else e.getY(0)
        val t = if (h >= 0) e.getHistoricalEventTime(h) else e.eventTime
        val dt = max(1L, t - lastT)
        val dpPerMs = hypot(x - px, y - py) / resources.displayMetrics.density / dt
        px = x; py = y; lastT = t
        val target = (1.15f - 0.25f * dpPerMs).coerceIn(0.5f, 1.15f)
        speedF += (target - speedF) * 0.3f
        return speedF
    }
    private var px = 0f
    private var py = 0f

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawing = true; gesture = false
                px = e.x; py = e.y; lastT = e.eventTime; speedF = 1f
                val stylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                val r = radius() * (if (stylus) (0.3f + 0.9f * e.pressure).coerceIn(0.3f, 1.2f) else 1f)
                lx = toCanvasX(e.x); ly = toCanvasY(e.y); lr = r
                nDabs = 0; run = 0f
                addDab(lx, ly, lr * taper())
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger: the stroke so far is dropped, and this is a pinch.
                if (drawing) { clearLive(); drawing = false; invalidate() }
                gesture = true
                pinchD = spread(e); pinchZ = zoom
                fx = mid(e, 0); fy = mid(e, 1)
            }
            MotionEvent.ACTION_MOVE -> {
                if (gesture && e.pointerCount >= 2) {
                    val nx = mid(e, 0); val ny = mid(e, 1)
                    val z = (pinchZ * spread(e) / pinchD).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    // The canvas point under the fingers stays under them.
                    val cx = (fx - tx) / zoom; val cy = (fy - ty) / zoom
                    zoom = z
                    tx = nx - cx * zoom; ty = ny - cy * zoom
                    fx = nx; fy = ny
                    clampView(); invalidate()
                } else if (drawing) {
                    val r0 = radius()
                    for (h in 0 until e.historySize) lineTo(toCanvasX(e.getHistoricalX(0, h)), toCanvasY(e.getHistoricalY(0, h)), r0 * widthAt(e, 0, h))
                    lineTo(toCanvasX(e.x), toCanvasY(e.y), r0 * widthAt(e, 0, -1))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (drawing) {
                    drawing = false
                    commit()
                    push(Action(dabs.copyOf(nDabs * 3), nDabs))
                    invalidate()
                }
                gesture = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (drawing) { clearLive(); drawing = false; invalidate() }
                gesture = false
            }
        }
        return true
    }

    private fun spread(e: MotionEvent) = max(1f, hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)))
    private fun mid(e: MotionEvent, axis: Int) = if (axis == 0) (e.getX(0) + e.getX(1)) / 2 else (e.getY(0) + e.getY(1)) / 2
}

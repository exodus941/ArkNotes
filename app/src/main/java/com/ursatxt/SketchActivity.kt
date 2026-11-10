package com.ursatxt

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File
import java.util.concurrent.Executors

/**
 * One sketch. A bare canvas under a row of tools: Return, Undo, Redo, Wipe, the brush
 * size, Invert and Delete. Every finished stroke saves the PNG, so there is no Save.
 */
class SketchActivity : Activity() {

    private lateinit var file: File
    private var isNew = false
    private var made = false          // the file exists on disk
    private lateinit var canvas: SketchCanvas
    private lateinit var frame: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var modal: Modal
    private val buttons = HashMap<String, ImageView>()
    private lateinit var brush: BrushButton
    private var slider: FrameLayout? = null
    private val saver = Executors.newSingleThreadExecutor()
    @Volatile private var pending: Array<Bitmap?>? = null
    @Volatile private var pendingInv = false
    private var gone = false
    private val onTheme: () -> Unit = { paint() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Theme.load(this)
        window.edgeToEdge()
        file = File(intent.getStringExtra("path") ?: run { finish(); return })
        isNew = intent.getBooleanExtra("new", false)
        made = file.exists()

        // A new canvas is twice the screen's full size, portrait.
        val (sw, sh) = screen()
        val pic = if (made) decode(file) else null
        val w = pic?.width ?: 2 * minOf(sw, sh)
        val h = pic?.height ?: 2 * maxOf(sw, sh)
        canvas = SketchCanvas(this, w, h)
        val prefs = getSharedPreferences("ursa", MODE_PRIVATE)
        canvas.size = prefs.getInt("brush", 3)
        // Invert carries over: a new sketch starts the way the last sketch was left, and
        // opening one makes its state the one the next new sketch takes.
        canvas.inverted = if (made) Sketches.flag(this, file, "inv") else prefs.getBoolean("sketchInv", false)
        prefs.edit().putBoolean("sketchInv", canvas.inverted).apply()
        pic?.let { canvas.load(it); it.recycle() }
        if (made) Sketches.touch(this, file)
        canvas.onChange = { strokeDone() }

        frame = FrameLayout(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(4), dpi(8), dpi(4), dpi(8))
        }
        fun tool(key: String, icon: Int, name: String, act: () -> Unit) = ImageView(this).apply {
            setImageResource(icon)
            background = ripple()
            setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
            contentDescription = name
            setOnClickListener { act() }
            buttons[key] = this
            bar.addView(this, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        }
        tool("back", R.drawable.ic_back, "Return") { leave() }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tool("undo", R.drawable.ic_undo, "Undo") { canvas.undo() }
        tool("redo", R.drawable.ic_redo, "Redo") { canvas.redo() }
        tool("wipe", R.drawable.ic_wipe, "Wipe") { canvas.wipe() }
        brush = BrushButton().apply {
            background = ripple()
            contentDescription = "Brush size"
            setOnClickListener { toggleSlider() }
        }
        bar.addView(brush, LinearLayout.LayoutParams(dpi(56), dpi(48)))
        tool("invert", R.drawable.ic_invert, "Invert Canvas") { invert() }
        tool("delete", R.drawable.ic_delete, "Delete") { askDelete() }
        content.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(canvas, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        frame.setOnApplyWindowInsetsListener { _, ins ->
            val s = ins.safe()
            content.setPadding(s[0], s[1], s[2], s[3])
            ins
        }
        setContentView(frame)
        modal = Modal(this, frame, content)
        Theme.listeners += onTheme
        paint()
    }

    private fun screen(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val r = windowManager.maximumWindowMetrics.bounds
            return r.width() to r.height()
        }
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun decode(f: File): Bitmap? = try {
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
    } catch (_: OutOfMemoryError) { null }

    private fun paint() {
        frame.setBackgroundColor(Theme.bg())
        window.decorView.setBackgroundColor(Theme.bg())
        bar.setBackgroundColor(Theme.bg())
        canvas.backdrop = Theme.backdrop()
        for ((k, b) in buttons) {
            val off = (k == "undo" && !canvas.canUndo) || (k == "redo" && !canvas.canRedo)
            b.setColorFilter(Theme.text())
            b.isEnabled = !off
            b.animate().alpha(if (off) 0.38f else 1f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
            (b.background as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        }
        (brush.background as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        brush.invalidate()
        slider?.let { paintSlider(it) }
        Theme.applyBars(window)
    }

    // ---- saving ----

    /** After every stroke, undo, redo or wipe: the PNG is written on its own thread. */
    private fun strokeDone() {
        paint()
        if (!made && canvas.empty) return
        queueSave()
    }

    private fun queueSave() {
        val busy = pending != null
        pending = canvas.snapshot()
        pendingInv = canvas.inverted
        if (!busy) saver.execute { drain() }
    }

    private fun drain() {
        while (true) {
            val tiles = pending ?: return
            val inv = pendingInv
            val pic = canvas.render(tiles, inv)
            val tmp = File(file.parentFile, ".${file.name}.tmp")
            try {
                tmp.outputStream().use { pic.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            } catch (_: Exception) { tmp.delete() }
            pic.recycle()
            // The index is written here, on the saving thread, so it is in place before the
            // list reads it, even when the sketch closes straight after a stroke.
            synchronized(Sketches) {
                if (!made) { made = true; Sketches.added(this, file) }
                Sketches.edit(this, file) { if (inv) it.put("inv", true) else it.remove("inv") }
            }
            if (pending === tiles) pending = null
        }
    }

    private fun invert() {
        canvas.inverted = !canvas.inverted
        getSharedPreferences("ursa", MODE_PRIVATE).edit().putBoolean("sketchInv", canvas.inverted).apply()
        if (made) queueSave()
    }

    // ---- the brush size ----

    /** A dot the size of the stroke, up to 20 dp, and the number beside it. */
    private inner class BrushButton : View(this@SketchActivity) {
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(15f); isFakeBoldText = true }
        override fun onDraw(c: Canvas) {
            dot.color = Theme.text(); text.color = Theme.text()
            val d = SketchCanvas.SIZES_DP[canvas.size - 1].coerceAtMost(20f) * resources.displayMetrics.density
            val cx = dp(20f); val cy = height / 2f
            c.drawCircle(cx, cy, d / 2, dot)
            c.drawText(canvas.size.toString(), dp(34f), cy - (text.ascent() + text.descent()) / 2, text)
        }
    }

    private fun toggleSlider() { if (slider == null) openSlider() else closeSlider() }

    /**
     * A vertical slider under the brush button, 5 at the top and 1 at the bottom. It
     * fades in and out over 250 ms; a tap outside closes it.
     */
    private fun openSlider() {
        val catcher = FrameLayout(this).apply { isClickable = true; setOnClickListener { closeSlider() } }
        val track = SizeTrack()
        val card = FrameLayout(this).apply {
            elevation = dp(8f)
            setPadding(dpi(8), dpi(12), dpi(8), dpi(12))
            addView(track, FrameLayout.LayoutParams(dpi(40), dpi(200)))
            isClickable = true
        }
        catcher.addView(card, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        frame.addView(catcher, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val at = IntArray(2); brush.getLocationInWindow(at)
        card.x = at[0] + (brush.width - dpi(56)) / 2f
        card.y = at[1] + brush.height + dp(4f)
        catcher.alpha = 0f
        catcher.animate().alpha(1f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
        slider = catcher
        paintSlider(catcher)
    }

    private fun paintSlider(s: FrameLayout) {
        val card = s.getChildAt(0)
        card.background = pill(Theme.popup(), 12f).apply { setStroke(dpi(1), Theme.outline()) }
        (card as FrameLayout).getChildAt(0).invalidate()
    }

    private fun closeSlider() {
        val s = slider ?: return
        slider = null
        s.isClickable = false
        s.animate().alpha(0f).setDuration(Motion.MS).setInterpolator(Motion.FADE).withEndAction { frame.removeView(s) }.start()
    }

    /** Five stops on a line; drag or tap to pick. */
    private inner class SizeTrack : View(this@SketchActivity) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private fun yOf(k: Int) = paddingTop + dp(12f) + (height - dp(24f)) * (5 - k) / 4f
        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            line.color = Theme.line()
            c.drawLine(cx, yOf(5), cx, yOf(1), line)
            for (k in 1..5) {
                val r = SketchCanvas.SIZES_DP[k - 1].coerceAtMost(20f) * resources.displayMetrics.density / 2
                dot.color = if (k == canvas.size) Theme.accentInk() else Theme.sub()
                c.drawCircle(cx, yOf(k), maxOf(r, dp(3f)), dot)
            }
        }
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val k = (1..5).minByOrNull { kotlin.math.abs(yOf(it) - e.y) } ?: return true
            if (k != canvas.size) {
                canvas.size = k
                getSharedPreferences("ursa", MODE_PRIVATE).edit().putInt("brush", k).apply()
                invalidate(); brush.invalidate()
            }
            if (e.actionMasked == MotionEvent.ACTION_UP) closeSlider()
            return true
        }
    }

    // ---- leaving ----

    private fun askDelete() {
        modal.confirm("Delete sketch?", "“${file.name.removeSuffix(".png")}” will be deleted from this device. This cannot be undone.", "Delete") {
            pending = null
            gone = true
            saver.shutdown()
            saver.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)
            val name = file.name.removeSuffix(".png")
            val ok = !file.exists() || Sketches.delete(this, file)
            setResult(RESULT_OK, Intent().putExtra("note", if (ok) "Deleted “$name”" else "Couldn’t delete “$name”"))
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun leave() {
        if (gone) return
        gone = true
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            modal.showing -> modal.cancel()
            slider != null -> closeSlider()
            else -> leave()
        }
    }

    override fun onResume() { super.onResume(); Updater.resumed(this, home = false) }
    override fun onPause() { super.onPause(); Updater.paused(this) }

    override fun onDestroy() {
        Theme.listeners -= onTheme
        // The last save finishes before the list reads the file.
        if (!saver.isShutdown) { saver.shutdown(); saver.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS) }
        super.onDestroy()
    }
}

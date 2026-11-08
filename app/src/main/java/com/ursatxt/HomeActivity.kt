package com.ursatxt

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : Activity() {

    private companion object {
        /** The path strip's height and the gap under it, in dp. */
        const val CRUMB_H = 48
        const val CRUMB_GAP = 16
        /** The bottom row: buttons 56 dp tall, 12 dp apart. */
        const val BTN = 56
        const val GAP = 12
    }

    private val pickZip = 1
    private val openNote = 2
    private val askFiles = 3

    /** One tab: a library, the folder it shows, and its own list. */
    private inner class Page(val lib: Library) {
        lateinit var dir: File
        var items: List<Entry> = emptyList()
        lateinit var list: ListView
        val adapter = Adapter(this)
        val spacer = View(this@HomeActivity).apply { layoutParams = AbsListView.LayoutParams(MATCH_PARENT, 0) }
        lateinit var empty: TextView
        lateinit var view: FrameLayout
        var lastQuery = ""
    }

    private val pages by lazy { listOf(Page(Notes), Page(Sketches)) }
    private var cur = 0
    private val page get() = pages[cur]
    private val lib get() = page.lib
    private var dir: File
        get() = page.dir
        set(v) { page.dir = v }
    private val list get() = page.list
    private lateinit var tabs: LinearLayout
    private lateinit var tabBar: View
    private lateinit var tabBar2: View
    private lateinit var pager: Pager

    private lateinit var frame: FrameLayout
    private lateinit var content: FrameLayout  // everything the modal dims and blurs
    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var version: TextView
    private lateinit var sortBtn: ImageView
    private lateinit var moreBtn: ImageView
    private lateinit var sticky: LinearLayout
    private lateinit var crumbBox: FrameLayout
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var crumbs: LinearLayout
    private lateinit var search: EditText
    private lateinit var searchBox: LinearLayout
    private lateinit var searchIcon: ImageView
    private lateinit var clear: ImageView
    private lateinit var topFade: Vignette
    private lateinit var bottomFade: Vignette
    private lateinit var modal: Modal
    private lateinit var menu: Menu
    private val onTheme: () -> Unit = { applyTheme() }

    private val prefs by lazy { getSharedPreferences("ursa", MODE_PRIVATE) }
    private var sortKey: String
        get() = prefs.getString("sort", "opened") ?: "opened"
        set(v) { prefs.edit().putString("sort", v).apply() }
    private var ascending: Boolean
        get() = prefs.getBoolean("asc", false)
        set(v) { prefs.edit().putBoolean("asc", v).apply() }
    private var foldersFirst: Boolean
        get() = prefs.getBoolean("foldersFirst", true)
        set(v) { prefs.edit().putBoolean("foldersFirst", v).apply() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Theme.load(this)
        window.edgeToEdge()
        for (p in pages) p.dir = p.lib.root(this)
        frame = FrameLayout(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        frame.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        content.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(20), dpi(12), dpi(8), dpi(8))
        }
        title = label("UrsaNotes", 24f, Theme.text(), bold = true)
        version = label(BuildConfig.VERSION_NAME, 13f, Theme.text(), bold = true)
        // TOP gravity, not centre: only then does LinearLayout line the two baselines up.
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(title)
            addView(version, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dpi(8) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        sortBtn = iconButton(R.drawable.ic_sort, "Sort") { sortMenu(it) }
        header.addView(sortBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        moreBtn = iconButton(R.drawable.ic_more, "Options") { mainMenu(it) }
        header.addView(moreBtn, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        root.addView(header)
        root.addView(buildTabs(), LinearLayout.LayoutParams(MATCH_PARENT, dpi(48)))

        val body = FrameLayout(this)
        pager = Pager()
        for (p in pages) {
            p.view = FrameLayout(this)
            p.list = ListView(this).apply {
                divider = null
                selector = getDrawable(android.R.color.transparent)
                clipToPadding = false
                addHeaderView(p.spacer, null, false)
                adapter = p.adapter
                setOnItemClickListener { _, _, pos, _ ->
                    val e = p.items.getOrNull(pos - headerViewsCount) ?: return@setOnItemClickListener
                    if (e.dir) go(e.file) else open(p, e.file)
                }
                setOnScrollListener(object : AbsListView.OnScrollListener {
                    override fun onScrollStateChanged(v: AbsListView, s: Int) {
                        if (s == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
                    }
                    override fun onScroll(v: AbsListView, first: Int, count: Int, total: Int) = updateFades()
                })
            }
            p.view.addView(p.list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            p.empty = label("", 15f, Theme.sub()).apply { gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE; setPadding(dpi(32), 0, dpi(32), 0) }
            p.view.addView(p.empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            pager.addView(p.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        body.addView(pager, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        topFade = Vignette(this, fromBottom = false)
        body.addView(topFade, FrameLayout.LayoutParams(MATCH_PARENT, dpi(96), Gravity.TOP))
        bottomFade = Vignette(this, fromBottom = true)
        body.addView(bottomFade, FrameLayout.LayoutParams(MATCH_PARENT, dpi(48), Gravity.BOTTOM))
        sticky = buildSticky()
        body.addView(sticky, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        sticky.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        buildBar()
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }

        frame.setOnApplyWindowInsetsListener { _, ins ->
            val s = ins.safe()
            root.setPadding(s[0], s[1], s[2], 0)
            @Suppress("DEPRECATION")
            val ime = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.ime()).bottom else ins.systemWindowInsetBottom
            imeInset = ime
            barInsets(s[3])
            place()
            ins
        }
        setContentView(frame)
        modal = Modal(this, frame, content)
        menu = Menu(this, frame)
        Theme.listeners += onTheme
        applyTheme()
        frame.post { askStorage() }
    }

    // ---- tabs ----

    /**
     * Yeetlist's band: tabs of equal width, 48 dp tall, the chosen one's label in the text
     * colour over a 2 dp accent bar on the band's hairline. The bar follows a swipe.
     */
    private fun buildTabs(): View {
        val band = FrameLayout(this)
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(R.drawable.ic_notes to "Notes", R.drawable.ic_sketch to "Sketches").forEachIndexed { k, (icon, name) ->
            tabs.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = ripple()
                contentDescription = name
                addView(ImageView(context).apply { setImageResource(icon); tag = "icon" }, LinearLayout.LayoutParams(dpi(20), dpi(20)))
                addView(label(name, 14f, Theme.sub(), bold = true).apply { tag = "text"; setPadding(dpi(8), 0, 0, 0) })
                setOnClickListener { select(k) }
            }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
        band.addView(tabs, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        band.addView(View(this).apply { tag = "rule" }, FrameLayout.LayoutParams(MATCH_PARENT, dpi(1), Gravity.BOTTOM))
        tabBar = View(this)
        band.addView(tabBar, FrameLayout.LayoutParams(0, dpi(2), Gravity.BOTTOM))
        // Its twin enters from the far side while a swipe wraps past the last tab.
        tabBar2 = View(this)
        band.addView(tabBar2, FrameLayout.LayoutParams(0, dpi(2), Gravity.BOTTOM))
        band.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeTabs(pager.pos) }
        return band
    }

    /** [p] wrapped into 0 until the page count: 0 is Notes, 1 Sketches, and 1.5 halfway back to Notes. */
    private fun wrap(p: Float): Float { val n = pages.size.toFloat(); return ((p % n) + n) % n }

    /** [pos] runs 0 (Notes) to 1 (Sketches) as the pages slide, and on round past either end. */
    private fun placeTabs(pos: Float) {
        val w = tabs.width / pages.size
        for (b in listOf(tabBar, tabBar2)) if (b.layoutParams.width != w) { b.layoutParams.width = w; b.requestLayout() }
        val at = wrap(pos)
        tabBar.translationX = at * w
        tabBar2.translationX = (at - pages.size) * w
        paintTabs()
    }

    private fun paintTabs() {
        if (!::tabs.isInitialized) return
        val pos = if (::pager.isInitialized) wrap(pager.pos) else 0f
        tabBar.setBackgroundColor(Theme.accentInk())
        tabBar2.setBackgroundColor(Theme.accentInk())
        (tabBar.parent as View).findViewWithTag<View>("rule").setBackgroundColor(Theme.line())
        for (k in 0 until tabs.childCount) {
            val t = tabs.getChildAt(k)
            // The label goes from grey to the text colour as its page slides in, from either side.
            val d = kotlin.math.abs(pos - k).let { minOf(it, pages.size - it) }
            val on = (1f - d).coerceIn(0f, 1f)
            val ink = android.animation.ArgbEvaluator().evaluate(on, Theme.sub(), Theme.text()) as Int
            t.findViewWithTag<TextView>("text").setTextColor(ink)
            t.findViewWithTag<ImageView>("icon").setColorFilter(ink)
            (t.background as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        }
    }

    /** To tab [k]: the pages slide %ease250%, and the path, search and up button follow it. */
    private fun select(k: Int) {
        pager.settle(k)
    }

    /** Called once a slide lands on [k]. */
    private fun landed(k: Int) {
        if (k == cur) return
        hideKeyboard()
        cur = k
        if (search.text.isNotEmpty()) search.setText("")
        search.hint = if (lib === Notes) "Search notes" else "Search sketches"
        buildCrumbs(dir)
        val inside = dir != lib.root(this)
        showCrumbs(inside)
        showUp(inside)
        refresh()
    }

    /**
     * The pages side by side on a loop: past the last comes the first again. A sideways drag
     * that beats the vertical one by 2 to 1 moves them under the finger; letting go lands on
     * the nearer page, or the one a fling points to, over %ease250%.
     */
    private inner class Pager : FrameLayout(this@HomeActivity) {
        var pos = 0f; private set
        private var downX = 0f
        private var downY = 0f
        private var from = 0f
        private var dragging = false
        private var vel: android.view.VelocityTracker? = null
        private var anim: ValueAnimator? = null
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            arrange()
        }

        private fun arrange() {
            val n = childCount
            for (k in 0 until n) {
                // Each page takes the nearer of its places on the loop.
                var d = (k - pos) % n
                if (d >= n / 2f) d -= n
                if (d < -n / 2f) d += n
                getChildAt(k).translationX = d * width
            }
            placeTabs(pos)
        }

        private fun moveTo(p: Float) { pos = p; arrange() }

        /** Slides to [k], which may lie one page past either end; it lands on that page's place on the loop. */
        fun settle(k: Int) {
            anim?.cancel()
            val to = k.toFloat()
            val n = childCount
            val home = ((k % n) + n) % n
            if (pos == to) { moveTo(home.toFloat()); landed(home); return }
            anim = ValueAnimator.ofFloat(pos, to).apply {
                duration = (Motion.MS * kotlin.math.abs(to - pos)).toLong().coerceAtLeast(120)
                interpolator = Motion.IN
                addUpdateListener { moveTo(it.animatedValue as Float) }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) { if (pos == to) { moveTo(home.toFloat()); landed(home) } }
                })
                start()
            }
        }

        /** True once a move is a sideways drag. Both paths ask it: a touch on a card
         *  reaches the pager through interception, a touch on empty space directly. */
        private fun startsDrag(e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; dragging = false
                    vel?.recycle(); vel = android.view.VelocityTracker.obtain(); vel?.addMovement(e)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    vel?.addMovement(e)
                    val dx = e.x - downX; val dy = e.y - downY
                    if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > 2 * kotlin.math.abs(dy)) {
                        dragging = true; anim?.cancel(); from = pos; downX = e.x
                        hideKeyboard()
                        return true
                    }
                }
            }
            return false
        }

        override fun onInterceptTouchEvent(e: android.view.MotionEvent) = startsDrag(e)

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            if (!dragging) { startsDrag(e); return true }
            vel?.addMovement(e)
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_MOVE -> moveTo(from - (e.x - downX) / width)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    vel?.computeCurrentVelocity(1000)
                    val v = vel?.xVelocity ?: 0f
                    val fling = dp(600f)
                    val k = when {
                        v < -fling -> kotlin.math.ceil(pos.toDouble()).toInt()
                        v > fling -> kotlin.math.floor(pos.toDouble()).toInt()
                        else -> kotlin.math.round(pos).toInt()
                    }
                    settle(k)
                }
            }
            return true
        }
    }

    private fun iconButton(icon: Int, name: String, act: (View) -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        background = ripple()
        setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
        contentDescription = name
        setOnClickListener { act(it) }
    }

    private fun buildSticky() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        // 16 dp under the tabs, the same as under the search box.
        setPadding(dpi(16), dpi(16), dpi(16), dpi(12))

        // The path, when inside a folder: it opens down from under the title bar, %ease250%.
        crumbs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // A strip with its own fill, 48 dp tall like the search box, with a 16 dp gap under
        // it so a press on a crumb cannot land on search.
        crumbScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dpi(4), 0, dpi(4), 0)
            clipToPadding = false
            addView(crumbs, FrameLayout.LayoutParams(WRAP_CONTENT, dpi(CRUMB_H)))
        }
        crumbBox = FrameLayout(context).apply { clipChildren = true; clipToPadding = true }
        crumbBox.addView(crumbScroll, FrameLayout.LayoutParams(MATCH_PARENT, dpi(CRUMB_H), Gravity.TOP))
        addView(crumbBox, LinearLayout.LayoutParams(MATCH_PARENT, 0))

        searchBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), 0, dpi(4), 0)
        }
        searchIcon = ImageView(context).apply { setImageResource(R.drawable.ic_search) }
        searchBox.addView(searchIcon, LinearLayout.LayoutParams(dpi(20), dpi(20)))
        search = EditText(context).apply {
            hint = "Search notes"
            textSize = 16f
            isSingleLine = true
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dpi(12), dpi(12), dpi(8), dpi(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh()
            })
            setOnEditorActionListener { _, id, ev ->
                val enter = ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
                if (id == EditorInfo.IME_ACTION_SEARCH || enter) { hideKeyboard(); true } else false
            }
        }
        searchBox.addView(search, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        clear = iconButton(R.drawable.ic_close, "Clear search") {
            search.setText("")
            hideKeyboard()
            list.setSelection(0)
        }.apply { visibility = View.GONE }
        searchBox.addView(clear, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        addView(searchBox, LinearLayout.LayoutParams(MATCH_PARENT, dpi(48)))
    }

    /** List starts under the sticky block; the top vignette hangs from its lower edge. */
    private fun place() {
        val h = sticky.height
        for (p in pages) {
            if (p.spacer.layoutParams.height != h) { p.spacer.layoutParams.height = h; p.spacer.requestLayout() }
            // The list ends above the row; an open keyboard covers more, so the list keeps that clear too.
            p.list.setPadding(0, 0, 0, maxOf(0, imeInset - bar.height) + dpi(8))
            p.empty.translationY = h + dp(24f)
        }
        topFade.translationY = h.toFloat()
        updateFades()
    }

    private fun updateFades() {
        if (!::bottomFade.isInitialized) return
        topFade.show(list.canScrollVertically(-1))
        bottomFade.show(list.canScrollVertically(1))
    }

    override fun onDestroy() {
        super.onDestroy()
        Theme.listeners -= onTheme
    }

    /** Runs on every frame of a theme change; touches only colours. */
    private fun applyTheme() {
        root.setBackgroundColor(Theme.bg())
        window.decorView.setBackgroundColor(Theme.bg())
        sticky.setBackgroundColor(Theme.bg())
        title.setTextColor(Theme.text())
        version.setTextColor(Theme.text())
        for (b in listOf(sortBtn, moreBtn, clear)) {
            b.setColorFilter(Theme.text())
            (b.background as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        }
        searchBox.background = pill(Theme.button(), 14f)
        searchIcon.setColorFilter(Theme.sub())
        search.setTextColor(Theme.text())
        search.setHintTextColor(Theme.sub())
        paintCrumbs()
        paintBar()
        paintTabs()
        for (p in pages) {
            p.empty.setTextColor(Theme.sub())
            for (i in 0 until p.list.childCount) paintCard(p.list.getChildAt(i))
        }
        topFade.invalidate(); bottomFade.invalidate()
        Theme.applyBars(window)
    }

    // ---- where we are ----

    private fun atRoot() = dir == lib.root(this)

    /** Into [to], with the list fading out and back in, 250 ms in all. */
    private fun go(to: File) {
        if (to == dir) return
        hideKeyboard()
        if (search.text.isNotEmpty()) search.setText("")
        val wasRoot = atRoot()
        list.animate().cancel()
        list.animate().alpha(0f).setDuration(Motion.MS / 2).setInterpolator(Motion.FADE).withEndAction {
            dir = to
            lib.touch(this, to)
            refresh()
            list.setSelection(0)
            list.animate().alpha(1f).setDuration(Motion.MS / 2).setInterpolator(Motion.FADE).start()
        }.start()
        buildCrumbs(to)
        if (wasRoot != (to == lib.root(this))) {
            showCrumbs(wasRoot)
            showUp(wasRoot)
        }
    }

    private fun up() { if (!atRoot()) dir.parentFile?.let { go(it) } }

    private fun buildCrumbs(at: File) {
        crumbs.removeAllViews()
        val r = lib.root(this)
        val chain = ArrayList<File>()
        var f: File? = at
        while (f != null && f != r) { chain.add(0, f); f = f.parentFile }
        chain.add(0, r)
        chain.forEachIndexed { k, d ->
            if (k > 0) crumbs.addView(ImageView(this).apply { setImageResource(R.drawable.ic_chevron); tag = "chev" },
                LinearLayout.LayoutParams(dpi(16), dpi(16)).apply { marginStart = dpi(2); marginEnd = dpi(2) })
            crumbs.addView(label(if (d == r) "Root" else d.name, 14f, Theme.sub(), bold = true).apply {
                tag = if (k == chain.size - 1) "here" else "crumb"
                background = ripple()
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(12), 0, dpi(12), 0)
                setOnClickListener { go(d) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, dpi(CRUMB_H)))
        }
        paintCrumbs()
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun paintCrumbs() {
        crumbScroll.background = pill(Theme.card(), 14f)
        for (i in 0 until crumbs.childCount) {
            val v = crumbs.getChildAt(i)
            when (v.tag) {
                "chev" -> (v as ImageView).setColorFilter(Theme.sub())
                "here" -> (v as TextView).setTextColor(Theme.text())
                else -> (v as TextView).setTextColor(Theme.sub())
            }
            (v.background as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        }
    }

    private var crumbAnim: ValueAnimator? = null
    private fun showCrumbs(show: Boolean) {
        val lp = crumbBox.layoutParams as LinearLayout.LayoutParams
        val from = lp.height
        val full = dpi(CRUMB_H + CRUMB_GAP)
        val to = if (show) full else 0
        if (from == to && crumbAnim?.isRunning != true) return
        crumbAnim?.cancel()
        crumbAnim = ValueAnimator.ofInt(from, to).apply {
            duration = Motion.MS
            interpolator = if (show) Motion.IN else Motion.OUT
            addUpdateListener {
                lp.height = it.animatedValue as Int
                crumbScroll.alpha = lp.height / full.toFloat()
                crumbBox.requestLayout()
            }
            start()
        }
    }

    // ---- the list ----

    /** Lists [p]'s folder; search narrows only the tab on screen. */
    private fun refresh(p: Page = page) {
        val lib = p.lib
        if (!p.dir.exists()) p.dir = lib.root(this)
        val q = if (p === page) search.text.toString().trim() else ""
        if (p === page) clear.fade(search.text.isNotEmpty())
        val all = if (q.isEmpty()) lib.list(this, p.dir) else lib.search(this, p.dir, q)
        p.items = sorted(all)
        val what = if (lib === Notes) "notes" else "sketches"
        p.empty.text = when {
            q.isNotEmpty() && p.items.isEmpty() -> if (lib === Notes) "No notes contain “$q”" else "No sketch names contain “$q”"
            p.items.isEmpty() -> if (p.dir == lib.root(this)) {
                if (lib === Notes) "No notes yet. The buttons below make notes, sketches and folders."
                else "No sketches yet. The buttons below make notes, sketches and folders."
            } else "This folder has no $what."
            else -> ""
        }
        p.empty.fade(p.empty.text.isNotEmpty())
        p.adapter.notifyDataSetChanged()
        if (q != p.lastQuery) p.list.setSelection(0)
        p.lastQuery = q
        p.list.post { updateFades() }
    }

    private fun refreshAll() { for (p in pages) refresh(p) }

    /** A note opens in the editor, a sketch on the canvas. */
    private fun open(p: Page, f: File) = if (p.lib === Notes) edit(f) else sketch(f, new = false)

    private fun sorted(all: List<Entry>): List<Entry> {
        val by: Comparator<Entry> = when (sortKey) {
            "name" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            "added" -> compareBy { it.added }
            "size" -> compareBy { it.size }
            else -> compareBy { it.opened }
        }
        val dirBy = if (ascending) by else by.reversed()
        // Pinned notes stay on top; then folders, when Folders First is on.
        var c = compareByDescending<Entry> { it.pinned }
        if (foldersFirst) c = c.thenByDescending { it.dir }
        return all.sortedWith(c.then(dirBy).thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    private fun stamp(t: Long): String {
        val now = System.currentTimeMillis()
        val ago = if (now - t < DateUtils.MINUTE_IN_MILLIS) "Just now"
            else DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS).toString()
        val clock = android.text.format.DateFormat.getTimeFormat(this).format(Date(t))
        val day = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(t))
        return "${ago.replaceFirstChar { it.uppercase() }}  ·  $day, $clock"
    }

    // ---- menus ----

    private fun mainMenu(anchor: View) {
        val rows = listOf(
            Menu.Row("Dark Mode", on = { !Theme.light }) {
                Theme.toggle(this)
                note(if (Theme.light) "Dark Mode off" else "Dark Mode on")
            },
            Menu.Row("Folders First", on = { foldersFirst }) {
                foldersFirst = !foldersFirst
                refreshAll()
                note(if (foldersFirst) "Folders First on" else "Folders First off")
            },
            Menu.Row("Export Notes", icon = R.drawable.ic_download) { exportAll() },
            Menu.Row("Import Notes", icon = R.drawable.ic_upload) { importPick() },
            Menu.Row("Check for Updates", icon = R.drawable.ic_refresh) { Updater.check(this) },
        )
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menuBack = null
        menu.show(rows, Gravity.TOP or Gravity.END, dpi(12), at[1])
    }

    private fun sortMenu(anchor: View) {
        fun key(k: String, t: String) = Menu.Row(t, mark = { if (sortKey == k) R.drawable.ic_radio_on else R.drawable.ic_radio_off }, stay = true) {
            sortKey = k; refreshAll()
        }
        fun way(asc: Boolean, t: String) = Menu.Row(t, mark = { if (ascending == asc) R.drawable.ic_radio_on else R.drawable.ic_radio_off }, stay = true) {
            ascending = asc; refreshAll()
        }
        val rows = listOf(
            key("name", "Name"), key("opened", "Date Accessed"), key("added", "Date Added"), key("size", "Size"),
            Menu.SEP,
            way(true, "Ascending"), way(false, "Descending"),
        )
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menuBack = null
        menu.show(rows, Gravity.TOP or Gravity.END, dpi(60), at[1])
    }

    private fun cardMenu(anchor: View, e: Entry) {
        val kind = if (e.dir) "folder" else if (lib === Notes) "note" else "sketch"
        val rows = listOf(
            Menu.Row(if (e.pinned) "Unpin" else "Pin", icon = if (e.pinned) R.drawable.ic_unpin else R.drawable.ic_pin) {
                lib.setPin(this, e.file, !e.pinned)
                refresh()
                note(if (e.pinned) "Unpinned “${e.name}”" else "Pinned “${e.name}”")
            },
            Menu.Row("Rename", icon = R.drawable.ic_rename) { rename(e) },
            Menu.Row("Move/Copy", icon = R.drawable.ic_copy) { moveCopy(e) },
        ) + (if (e.dir) emptyList() else listOf(
            Menu.Row("Share", icon = R.drawable.ic_share) { if (lib === Notes) shareMenu(anchor, e) else sketchShare(anchor, e) },
        )) + listOf(
            Menu.Row("Delete", icon = R.drawable.ic_delete) {
                val what = if (e.dir) "“${e.name}” and everything in it will be deleted from this device." else "“${e.name}” will be deleted from this device."
                modal.confirm("Delete $kind?", "$what This cannot be undone.", "Delete") {
                    if (lib.delete(this, e.file)) { refresh(); note("Deleted “${e.name}”") } else note("Couldn’t delete “${e.name}”")
                }
            },
        )
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menuBack = null
        menu.show(rows, Gravity.TOP or Gravity.END, dpi(12), at[1])
    }

    /** Where Back goes from a menu that replaced another; null closes it. */
    private var menuBack: (() -> Unit)? = null
    private val convert by lazy { Convert(this) }

    /**
     * The card menu's place taken by the editor's share menu, the same formats and
     * words. Return, or the Back button, puts the card menu back.
     */
    private fun shareMenu(anchor: View, e: Entry) {
        val back = { cardMenu(anchor, e) }
        fun md() = try { e.file.readText() } catch (_: Exception) { "" }
        fun file(ext: String) = "${e.name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(60).ifEmpty { "Untitled note" }}.$ext"
        fun save(name: String, mime: String, text: String) {
            val ok = saveAndShare(name, mime) { it.write(text.toByteArray()) }
            note(if (ok) "Saved “$name” to Downloads" else "Couldn’t save “$name” to Downloads")
        }
        val rows = listOf(
            Menu.Row("Return", icon = R.drawable.ic_back) { back() },
            Menu.Row("MD", icon = R.drawable.ic_download) { save(file("md"), "text/markdown", md()) },
            Menu.Row("HTML", icon = R.drawable.ic_download) {
                convert.run("noteToHtmlPage", e.name, md()) { save(file("html"), "text/html", it) }
            },
            Menu.Row("TXT", icon = R.drawable.ic_download) {
                convert.run("markdownToText", md()) { save(file("txt"), "text/plain", it) }
            },
            Menu.Row("Copy to Clipboard", icon = R.drawable.ic_copy) {
                val text = md().trim()
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText(e.name, text))
                note("Note copied to clipboard")
                sendText(text, e.name)
            },
            Menu.Row("External", icon = R.drawable.ic_share) { sendText(md().trim(), e.name) },
        )
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menu.show(rows, Gravity.TOP or Gravity.END, dpi(12), at[1])
        menuBack = back
    }

    /** A sketch's share menu: PNG and JPG save to Downloads and open the share sheet. */
    private fun sketchShare(anchor: View, e: Entry) {
        val back = { cardMenu(anchor, e) }
        val rows = listOf(
            Menu.Row("Return", icon = R.drawable.ic_back) { back() },
            Menu.Row("PNG", icon = R.drawable.ic_download) { shareSketch(this, e.file, "png", note = { note(it) }) },
            Menu.Row("JPG", icon = R.drawable.ic_download) { shareSketch(this, e.file, "jpg", note = { note(it) }) },
            Menu.Row("External", icon = R.drawable.ic_share) { shareSketch(this, e.file, "external", note = { note(it) }) },
        )
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menu.show(rows, Gravity.TOP or Gravity.END, dpi(12), at[1])
        menuBack = back
    }

    private fun sendText(text: String, title: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        startActivity(Intent.createChooser(i, null))
    }

    private val kindName get() = if (lib === Notes) "note" else "sketch"

    private fun rename(e: Entry) {
        modal.input(if (e.dir) "Rename folder" else "Rename $kindName", e.name, "Rename") { raw ->
            if (raw.isEmpty() || raw == e.name) return@input
            val to = lib.rename(this, e.file, raw)
            refresh()
            note("Renamed to “${to.name.removeSuffix(lib.EXT)}”")
        }
    }

    // ---- Move/Copy: the folder tree ----

    private var tree: Tree? = null

    private fun moveCopy(e: Entry) {
        val t = Tree(this, lib, lib.root(this), e.file)
        tree = t
        modal.custom("Move/Copy", t.view) { row, make, close ->
            val copy = make("Copy", Theme.accentInk()) {
                val into = t.picked ?: return@make
                close()
                val done = lib.copy(this, e.file, into)
                refresh()
                note(if (done != null) "Copied “${e.name}” to ${where(into)}" else "Couldn’t copy “${e.name}”")
            }
            val move = make("Move", Theme.accentInk()) {
                val into = t.picked ?: return@make
                close()
                val done = lib.move(this, e.file, into)
                refresh()
                note(if (done != null) "Moved “${e.name}” to ${where(into)}" else "Couldn’t move “${e.name}”")
            }
            row.addView(make("Cancel", Theme.sub()) { close() })
            row.addView(copy)
            row.addView(move)
            fun enable() {
                val p = t.picked
                val okCopy = p != null
                val okMove = p != null && p != e.file.parentFile
                for ((v, ok) in listOf(copy to okCopy, move to okMove)) {
                    v.isEnabled = ok
                    v.animate().alpha(if (ok) 1f else 0.38f).setDuration(Motion.MS).setInterpolator(Motion.FADE).start()
                }
            }
            copy.alpha = 0.38f; move.alpha = 0.38f; copy.isEnabled = false; move.isEnabled = false
            t.onPick = { enable() }
            // The tree opens on the folder the item is in, with Create New Folder Here under it.
            t.pick(e.file.parentFile ?: lib.root(this))
        }
    }

    private fun where(f: File) = if (f == lib.root(this)) "Root" else "“${f.name}”"

    // ---- the bottom row ----

    private lateinit var bar: LinearLayout
    private lateinit var makers: List<ImageView>
    private lateinit var upBtn: ImageView
    private lateinit var upSlot: FrameLayout
    private var imeInset = 0

    /**
     * New Note, New Sketch and New Folder, right to left, sharing a row along the bottom in
     * the page's own colour. Inside a folder a square up button opens at its start. The list
     * and its bottom shadow end above the row.
     */
    private fun buildBar() {
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun button(icon: Int, name: String, act: () -> Unit) = ImageView(this).apply {
            setImageResource(icon)
            // A plain rounded rectangle, 14 dp like the search box: a squircle stretched this wide reads as a pill.
            background = pill(0, 14f)
            foreground = ripple()
            clipToOutline = true
            setPadding(dpi(16), dpi(16), dpi(16), dpi(16))
            contentDescription = name
            setOnClickListener { act() }
        }
        upBtn = button(R.drawable.ic_up, "Up a folder") { up() }
        upSlot = FrameLayout(this).apply { clipChildren = true; alpha = 0f }
        upSlot.addView(upBtn, FrameLayout.LayoutParams(dpi(BTN), dpi(BTN), Gravity.START))
        bar.addView(upSlot, LinearLayout.LayoutParams(0, dpi(BTN)))
        makers = listOf(
            button(R.drawable.ic_folder_plus, "New Folder") { makeFolder() },
            button(R.drawable.ic_sketch_plus, "New Sketch") { makeSketch() },
            button(R.drawable.ic_note_plus, "New Note") { makeNote() },
        )
        makers.forEachIndexed { k, b ->
            val lp = LinearLayout.LayoutParams(0, dpi(BTN), 1f)
            if (k > 0) lp.marginStart = dpi(GAP)
            bar.addView(b, lp)
        }
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        paintBar()
    }

    /** The row keeps 16 dp from the sides and 16 dp above the navigation bar or gesture line. */
    private fun barInsets(navBottom: Int) {
        bar.setPadding(dpi(16), dpi(8), dpi(16), navBottom + dpi(16))
    }

    private var upAnim: ValueAnimator? = null
    private var upShown = false

    /** Inside a folder the up button opens at the row's start, %ease250%; at the root it closes. */
    private fun showUp(show: Boolean) {
        if (show == upShown && upAnim?.isRunning != true) return
        upShown = show
        val lp = upSlot.layoutParams as LinearLayout.LayoutParams
        val full = dpi(BTN + GAP)
        upAnim?.cancel()
        upAnim = ValueAnimator.ofInt(lp.width, if (show) full else 0).apply {
            duration = Motion.MS
            interpolator = if (show) Motion.IN else Motion.OUT
            addUpdateListener {
                lp.width = it.animatedValue as Int
                upSlot.alpha = lp.width / full.toFloat()
                upSlot.requestLayout()
            }
            start()
        }
    }

    private fun paintBar() {
        if (!::bar.isInitialized) return
        bar.setBackgroundColor(Theme.bg())
        // The up button is grey: it moves you, it makes nothing.
        for (b in makers + upBtn) {
            val ink = if (b === upBtn) Theme.sub() else Theme.accentInk()
            (b.background as android.graphics.drawable.GradientDrawable).apply { setColor(Theme.bg()); setStroke(dpi(2), ink) }
            b.setColorFilter(ink)
            (b.foreground as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        }
    }

    // ---- making things ----

    private fun makeNote() {
        val f = Notes.newNote(this, pages[0].dir)
        edit(f)
    }

    /** The file is named now and written on the first stroke; a sketch left blank is never made. */
    private fun makeSketch() {
        val d = pages[1].dir
        sketch(Sketches.unique(d, Sketches.untitled, false), new = true)
    }

    private fun sketch(f: File, new: Boolean) {
        hideKeyboard()
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, SketchActivity::class.java).putExtra("path", f.absolutePath).putExtra("new", new), openNote)
        @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun makeFolder() {
        modal.input("New folder", "New Folder", "Create") { raw ->
            val f = lib.newFolder(this, dir, raw.ifEmpty { "New Folder" })
            refresh()
            note("Created “${f.name}”")
        }
    }

    private fun edit(f: File) {
        hideKeyboard()
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, EditorActivity::class.java).putExtra("path", f.absolutePath), openNote)
        @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ---- backup ----

    private fun exportAll() {
        val name = "UrsaNotes-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".zip"
        var n = 0
        val ok = saveAndShare(name, "application/zip") { n = Backup.export(this, it) }
        note(if (ok) "Exported ${count(n)} to Downloads as $name" else "Couldn’t save the backup to Downloads")
    }

    private fun count(n: Int) = if (n == 1) "1 item" else "$n items"

    private fun importPick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, pickZip)
    }

    // ---- storage access ----

    private fun askStorage() {
        if (Store.shared() && (Build.VERSION.SDK_INT >= 30 || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED)) return
        if (prefs.getBoolean("askedFiles", false)) return
        prefs.edit().putBoolean("askedFiles", true).apply()
        modal.choose("Keep notes in Documents?",
            "UrsaNotes saves each note as a .md file in Documents/UrsaNotes, where other apps can open them. Android asks you to allow file access for that. Until then, notes stay inside the app.",
            listOf(
                "Allow" to {
                    if (Build.VERSION.SDK_INT >= 30) {
                        try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                        catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    } else requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), askFiles)
                },
                "Not Now" to {},
            ))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (code == askFiles) { for (p in pages) p.dir = p.lib.root(this); refreshAll() }
    }

    // ---- system ----

    @Deprecated("Deprecated in Java")
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::menu.isInitialized && menu.showing && ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            // The menu is its own window, so a touch reaching this one is outside it: it only closes the menu.
            menu.dismiss(); swallow = true
        }
        if (swallow) { if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) swallow = false; return true }
        return super.dispatchTouchEvent(ev)
    }
    private var swallow = false

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val t = tree
        when {
            menu.showing && menuBack != null -> { val b = menuBack!!; menuBack = null; menu.dismiss(); b() }
            menu.showing -> menu.dismiss()
            modal.showing && t != null && t.editing -> t.stopEditing()
            modal.showing -> { modal.cancel(); tree = null }
            search.text.isNotEmpty() -> search.setText("")
            !atRoot() -> up()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        for (p in pages) {
            val r = p.lib.root(this)
            if (!p.dir.absolutePath.startsWith(r.absolutePath)) p.dir = r
        }
        buildCrumbs(dir); showCrumbs(dir != lib.root(this)); showUp(dir != lib.root(this))
        Notes.sweep(this, pages[0].dir)
        refreshAll()
        Updater.resumed(this, home = true)
    }

    override fun onPause() {
        super.onPause()
        Updater.paused(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(req, res, data)
        if (req == openNote) { data?.getStringExtra("note")?.let { note(it) }; refreshAll(); return }
        val uri = data?.data
        if (req == pickZip && res == RESULT_OK && uri != null) {
            val n = try { contentResolver.openInputStream(uri)?.use { Backup.import(this, it) } ?: -1 } catch (_: Exception) { -1 }
            refreshAll()
            note(if (n >= 0) "Imported ${count(n)}" else "Couldn’t read that file as an UrsaNotes backup")
        }
    }

    // ---- cards ----

    private inner class Adapter(val pg: Page) : BaseAdapter() {
        override fun getCount() = pg.items.size
        override fun getItem(p: Int): Any = pg.items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (pg.items[p].dir) 0 else 1

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val e = pg.items[p]
            val card = (convert as? FrameLayout) ?: card(e.dir)
            card.tag = e
            val box = card.getChildAt(0) as LinearLayout
            val texts = box.findViewWithTag<LinearLayout>("texts")
            val head = texts.getChildAt(0) as LinearLayout
            (head.getChildAt(0) as TextView).text = e.name
            box.findViewWithTag<View>("pin").visibility = if (e.pinned) View.VISIBLE else View.GONE
            if (e.dir) {
                val parts = ArrayList<String>()
                val (one, many) = if (pg.lib === Notes) "note" to "notes" else "sketch" to "sketches"
                parts += if (e.notes == 1) "1 $one" else "${e.notes} $many"
                if (e.folders > 0) parts += if (e.folders == 1) "1 folder" else "${e.folders} folders"
                (texts.getChildAt(1) as TextView).text = parts.joinToString("  ·  ")
            } else {
                val prev = texts.getChildAt(1) as TextView
                prev.text = e.preview
                prev.visibility = if (e.preview.isEmpty()) View.GONE else View.VISIBLE
                (texts.getChildAt(2) as TextView).text = stamp(e.opened)
            }
            box.findViewWithTag<View>("more").setOnClickListener { v -> cardMenu(v, e) }
            paintCard(card)
            return card
        }

        /** A card: a colour strip on its edge, the texts, and a ⋮. */
        private fun card(folder: Boolean) = FrameLayout(this@HomeActivity).apply {
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dpi(16), dpi(4), dpi(16), dpi(4))
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipToOutline = true
                foreground = ripple()
                tag = "box"
            }
            // The strip sits outside the card's 16 dp inset (Yeetlist's rule): 4 dp.
            box.addView(View(context).apply { tag = "strip" }, LinearLayout.LayoutParams(dpi(4), MATCH_PARENT))
            if (folder) box.addView(ImageView(context).apply { setImageResource(R.drawable.ic_folder); tag = "icon" },
                LinearLayout.LayoutParams(dpi(22), dpi(22)).apply { marginStart = dpi(16) })
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                tag = "texts"
                setPadding(dpi(16), dpi(14), 0, dpi(14))
            }
            val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(label("", 16f, Theme.text(), bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            texts.addView(head)
            if (folder) {
                texts.addView(label("", 12f, Theme.sub()).apply { setPadding(0, dpi(2), 0, 0) })
            } else {
                texts.addView(label("", 14f, Theme.sub()).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dpi(4), 0, 0); tag = "preview" })
                texts.addView(label("", 12f, Theme.sub()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dpi(6), 0, 0); tag = "date" })
            }
            box.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            // A pin, when pinned: the ⋮ glyph's 24 dp, centred on the same line, its glyph 12 dp
            // from the ⋮ glyph (the ⋮ carries 12 dp of padding inside its 48 dp target).
            box.addView(ImageView(context).apply { setImageResource(R.drawable.ic_pin); tag = "pin"; contentDescription = "Pinned" },
                LinearLayout.LayoutParams(dpi(24), dpi(24)).apply { marginStart = dpi(8) })
            box.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_more)
                background = ripple()
                setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
                contentDescription = if (folder) "Folder options" else if (pg.lib === Notes) "Note options" else "Sketch options"
                isFocusable = false
                tag = "more"
            }, LinearLayout.LayoutParams(dpi(48), dpi(48)).apply { marginEnd = dpi(4) })
            addView(box, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun hueColor(h: Int): Int {
        val hsv = floatArrayOf(h.toFloat(), 0.75f, 1f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun paintCard(v: View) {
        val card = v as? FrameLayout ?: return
        val e = card.tag as? Entry ?: return
        val box = card.getChildAt(0) as LinearLayout
        box.background = pill(Theme.card(), 12f)
        (box.foreground as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        box.findViewWithTag<View>("strip").setBackgroundColor(if (e.dir) Theme.accentInk() else hueColor(e.hue))
        box.findViewWithTag<ImageView>("icon")?.setColorFilter(Theme.accentInk())
        val texts = box.findViewWithTag<LinearLayout>("texts")
        val head = texts.getChildAt(0) as LinearLayout
        (head.getChildAt(0) as TextView).setTextColor(Theme.text())
        box.findViewWithTag<ImageView>("pin").setColorFilter(Theme.accentInk())
        for (i in 1 until texts.childCount) (texts.getChildAt(i) as TextView).setTextColor(Theme.sub())
        box.findViewWithTag<ImageView>("more").apply {
            setColorFilter(Theme.sub())
            (background as? RippleDrawable)?.setColor(ColorStateList.valueOf(Theme.ripple()))
        }
    }
}

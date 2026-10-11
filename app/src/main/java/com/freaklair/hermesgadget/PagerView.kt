package com.freaklair.hermesgadget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// Swipe-pages view: N gadget pages, one per profile/agent, all hot.
// Draw + touch arbitration in one custom view:
//  - vertical hold/press = core touch (hold = talk)
//  - horizontal drift beyond slop = page swipe (core gets a touch-cancel)
class PagerView(context: Context, private val engines: List<PageEngine>) : View(context) {

    interface Owner {
        fun onPageChanged(index: Int)
        fun onSettingsRequested()
    }
    var owner: Owner? = null
    var settingsPage = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val font = Typeface.MONOSPACE

    var currentPage = 0
        private set

    private var pageW = 0
    private var pageH = 0
    private val stripH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 34f, resources.displayMetrics)
    private val slop = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics)

    // strip hit areas, refreshed each draw (left/right per page name)
    private val stripBox = ArrayList<android.graphics.RectF>()
    private val longPress = android.os.Handler(android.os.Looper.getMainLooper())
    private var longPressIdx = -1
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        if (longPressIdx >= 0 && longPressIdx < engines.size) {
            engines[longPressIdx].muted = !engines[longPressIdx].muted
            longPressFired = true
            invalidate()
        }
        longPressIdx = -1
    }

    // headMode: robot-head visualization (MyBots) — skip the core-bitmap blit
    // and the black fill; the HeadView WebView below draws the head, and this
    // view keeps only the strip + touch arbitration.
    var headMode = false

    private var down = false
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var swipeApplied = false
    private var activeEngineTouch = false
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var invalidating = false

    init {
        keepScreenOn = true
        isFocusable = true
    }

    // Coalesced redraw requests from core threads (~60fps cap).
    fun poke() {
        if (invalidating) return
        invalidating = true
        postInvalidateOnAnimation()
        main.postDelayed({ invalidating = false }, 16)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        pageW = w
        pageH = (h - stripH).roundToInt()
    }

    private fun pageCount(): Int = engines.size + if (settingsPage) 1 else 0

    fun setPage(i: Int) {
        val clamped = i.coerceIn(0, pageCount() - 1)
        val changed = clamped != currentPage
        currentPage = clamped
        // ALWAYS re-apply foreground ownership, even on a same-page rebuild —
        // freshly created engines default to backgrounded and would otherwise
        // never get the mic gate opened (core then shows "Microphone unavailable").
        engines.forEachIndexed { idx, e -> e.setForeground(idx == currentPage) }
        if (changed) owner?.onPageChanged(currentPage)
        invalidate()
    }

    // scale-to-fit of the core panel inside the page; null until size known
    private fun stripHit(x: Float, y: Float): Int {
        if (y < pageH) return -1
        for (i in stripBox.indices) {
            val b = stripBox[i]
            if (x >= b.left && x <= b.right) return i
        }
        return -1
    }

    private fun panelRect(e: PageEngine): FloatArray? {
        val (w, h) = e.size()
        if (w <= 0 || h <= 0 || pageW <= 0) return null
        val scale = min(pageW.toFloat() / w, pageH.toFloat() / h)
        val ow = w * scale
        val oh = h * scale
        val ox = (pageW - ow) / 2f
        val oy = (pageH - oh) / 2f
        return floatArrayOf(ox, oy, ow, oh)
    }

    // HUD alignment: panel rect in screen coords + the core's own status band
    // height (11 * scale pixels, see Ui::layout). FloatArray = ox, oy, ow, band.
    fun hudBand(): FloatArray? {
        val e = engines.getOrNull(currentPage) ?: return null
        val r = panelRect(e) ?: return null
        val (w, h) = e.size()
        if (w <= 0 || h <= 0) return null
        val s = minOf(4, maxOf(1, minOf(w / 160, h / 120)))
        return floatArrayOf(r[0], r[1], r[2], 11 * s.toFloat() * (r[2] / w))
    }

    private fun toCore(e: PageEngine, x: Float, y: Float): Pair<Int, Int> {
        val r = panelRect(e) ?: return 0 to 0
        val (w, h) = e.size()
        val cx = ((x - r[0]) / r[2] * w).toInt().coerceIn(0, w - 1)
        val cy = ((y - r[1]) / r[3] * h).toInt().coerceIn(0, h - 1)
        return cx to cy
    }

    override fun onDraw(canvas: Canvas) {
        if (!headMode) canvas.drawColor(Color.BLACK)
        val i = currentPage
        if (i < engines.size) {
            val e = engines[i]
            val r = if (headMode) null else panelRect(e)
            if (r != null) {
                try {
                    val (w, h) = e.size()
                    val rows = e.rows(0, h)
                    if (rows.isNotEmpty()) {
                        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                        for (y in rows.indices) bmp.setPixels(rows[y], 0, w, 0, y, w, 1)
                        canvas.save()
                        canvas.translate(r[0], r[1])
                        canvas.scale(r[2] / w, r[3] / h)
                        canvas.drawBitmap(bmp, 0f, 0f, null)
                        canvas.restore()
                        bmp.recycle()
                    }
                } catch (_: Exception) { /* page warming up */ }
            }
        } else {
            drawSettingsPlaceholder(canvas)
        }
        drawStrip(canvas)
    }

    private fun drawSettingsPlaceholder(canvas: Canvas) {
        textPaint.color = Color.parseColor("#777779")
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("+", pageW / 2f, pageH / 2f - 30, textPaint)
        canvas.drawText("add profile", pageW / 2f, pageH / 2f + 5, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawStrip(canvas: Canvas) {
        val n = pageCount()
        val cy = pageH + stripH * 0.45f
        val nameSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        textPaint.textSize = nameSize
        textPaint.typeface = font
        val gap = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, resources.displayMetrics)
        val names = (0 until n).map { if (it < engines.size) engines[it].name else "+" }
        val widths = names.map { textPaint.measureText(it) }
        val total = widths.sum() + gap * (n - 1)
        var x = (pageW - total) / 2f
        stripBox.clear()
        val boxPad = gap / 2f
        for (k in 0 until n) {
            textPaint.color = if (k == currentPage) Color.parseColor("#E8E8EC") else Color.parseColor("#5A5A62")
            if (k < engines.size && engines[k].muted) {
                textPaint.color = if (k == currentPage) Color.parseColor("#66707A") else Color.parseColor("#3A3A42")
            }
            canvas.drawText(names[k], x, cy, textPaint)
            stripBox.add(android.graphics.RectF(x - boxPad, pageH.toFloat(),
                x + widths[k] + boxPad, pageH + stripH))
            x += widths[k] + gap
        }
        // unread markers: filled square under a background page that notified
        val dotR = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.5f, resources.displayMetrics)
        var dx2 = (pageW - total) / 2f
        val dy = cy + dotR * 3
        for (k in 0 until n) {
            if (k != currentPage && k < engines.size && engines[k].hasNotified.get()) {
                dotPaint.color = Color.parseColor("#E8C34A")
                canvas.drawRect(dx2, dy, dx2 + widths[k], dy + dotR * 1.6f, dotPaint)
            }
            dx2 += widths[k] + gap
        }
    }

    // ---- touch arbitration ---------------------------------------------------
    // Vertical/hold -> core (talk). Horizontal drift > slop -> swipe + cancel.

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val idx = currentPage.coerceAtMost(engines.size - 1)
        val engine = if (currentPage < engines.size) engines[idx] else null
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true; downX = ev.x; downY = ev.y
                dragging = false; swipeApplied = false
                longPressFired = false
                val si = stripHit(ev.x, ev.y)
                if (si >= 0) {
                    // strip: tap = jump to page, long-press = mute toggle
                    longPressIdx = if (si < engines.size) si else -1
                    if (longPressIdx >= 0) longPress.postDelayed(longPressRunnable, 350)
                    return true
                }
                longPressIdx = -1
                if (engine != null) {
                    activeEngineTouch = true
                    val (cx, cy2) = toCore(engine, ev.x, ev.y)
                    engine.touch(true, cx, cy2)
                } else activeEngineTouch = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!down) return true
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && abs(dx) > slop && abs(dx) > abs(dy)) {
                    dragging = true
                    longPress.removeCallbacks(longPressRunnable); longPressIdx = -1
                    if (activeEngineTouch) {
                        engine?.touch(false, 0, 0)  // swipe interrupts a talk
                        activeEngineTouch = false
                    }
                }
                if (dragging) {
                    // live preview: shift the drawn page by the drag fraction
                    invalidate()
                } else if (activeEngineTouch) {
                    val (cx, cy2) = toCore(engine!!, ev.x, ev.y)
                    engine.touch(true, cx, cy2)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPress.removeCallbacks(longPressRunnable)
                val lp = longPressIdx
                val fired = longPressFired
                longPressIdx = -1; longPressFired = false
                if (!dragging && !activeEngineTouch && !fired &&
                        ev.actionMasked == MotionEvent.ACTION_UP) {
                    if (lp >= 0) { setPage(lp); return true }          // strip tap (engine page)
                    val si = stripHit(ev.x, ev.y)
                    if (si >= 0) { if (si < pageCount()) setPage(si); return true }  // "+" page
                }
                if (dragging) {
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        val d = ev.x - downX
                        if (abs(d) > pageW / 4) setPage(currentPage + if (d < 0) 1 else -1)
                        else invalidate()
                    }
                } else if (activeEngineTouch) {
                    val (cx, cy2) = toCore(engine!!, ev.x, ev.y)
                    engine.touch(false, cx, cy2)
                } else if (ev.actionMasked == MotionEvent.ACTION_UP && currentPage >= engines.size) {
                    owner?.onSettingsRequested()
                }
                down = false; dragging = false; activeEngineTouch = false
                return true
            }
        }
        return true
    }
}

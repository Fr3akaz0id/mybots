package com.freaklair.hermesgadget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

// Top status strip drawn on canvas — same visual language as the pager
// (flat, dark rounded pills, teal accents) so it reads as part of the app
// instead of a stock-Button sticker on top. Left: wake-word pill. Center:
// clock + battery. Right: settings gear. All three are hit-zones; touches
// outside them fall through to the pager.
class HudOverlay(context: Context) : View(context) {

    var onGear: () -> Unit = {}
    var onWake: () -> Unit = {}
    @Volatile var wakeOn: Boolean = true
    // band geometry from PagerView: [ox, oy, ow, coreStatusBandH] — pills
    // hang BELOW the core's own title/ONLINE line so they never overlap it.
    @Volatile var bandProvider: () -> FloatArray? = { null }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val font = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    private val pillBg = 0x66101418.toInt()
    private val pillEdge = 0x33FFFFFF
    private val teal = Color.parseColor("#38D488")  // firmware kGreen (rgb565 61,214,140)
    private val dim = Color.parseColor("#5A5A62")
    private val fg = Color.parseColor("#E8E8EC")
    private val amber = Color.parseColor("#E6A23C")

    // pairing banner: set while the core waits for hub approval, cleared when
    // paired. The head replaced the core panel, so without this the code the
    // hub needs is invisible on screen.
    @Volatile var pairText: String? = null
    fun setPairBanner(t: String?) {
        if (pairText != t) { pairText = t; handler.post { invalidate() } }
    }

    private val dip = resources.displayMetrics.density
    private val h = 34f * dip          // pill height
    private val margin = 10f * dip
    private val pillR = 9f * dip
    private val padX = 12f * dip

    private var wakeBox = RectF()
    private var gearBox = RectF()
    private var statusBox = RectF()
    private var pressed: Int = 0        // 1 wake, 2 gear, 0 none

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { invalidate(); handler.postDelayed(this, 20_000) }
    }

    // battery state, refreshed via the sticky ACTION_BATTERY_CHANGED receiver
    private var battLevel = -1
    private var battCharge = false
    private val battReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i?.let {
                battLevel = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                battCharge = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            }
            invalidate()
        }
    }

    fun start() {
        handler.postDelayed(tick, 1000)
        context.registerReceiver(battReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }
    fun stop() {
        handler.removeCallbacks(tick)
        try { context.unregisterReceiver(battReceiver) } catch (_: Exception) {}
    }

    override fun onSizeChanged(w: Int, h2: Int, ow: Int, oh: Int) {
        layoutPills(w)
    }

    // Widths follow the CURRENT content (Wake vs Wake off, 3- vs 4-digit
    // clock, 0-100%) and the status pill is clamped into the gap between
    // wake and gear, so portrait never lets them collide.
    private fun layoutPills(w: Int) {
        val band = bandProvider()
        val top = if (band != null) band[1] + band[3] + 6f * dip else margin
        val y0 = top
        val y1 = y0 + h

        gearBox = RectF(w - margin - h, y0, w - margin, y1)

        paint.textSize = 14f * dip
        val wakeW = measureWake()
        wakeBox = RectF(margin, y0, minOf(margin + wakeW, w - margin - 2 * h), y1)

        val statusW = measureStatus()
        val lo = wakeBox.right + margin
        val hi = gearBox.left - margin
        val cx = if (hi - lo >= statusW) lo + (hi - lo) / 2f else w / 2f
        statusBox = RectF(cx - statusW / 2f, y0, cx + statusW / 2f, y1)
    }

    private fun wakeContent(): String = if (wakeOn) "Wake" else "Wake off"

    private fun measureWake(): Float {
        val ring = if (wakeOn) "\u25C9" else "\u25CE"
        return 2 * padX + paint.measureText(ring) + 6f * dip + paint.measureText(wakeContent())
    }

    private fun measureStatus(): Float {
        val now = java.util.Calendar.getInstance()
        val clock = String.format("%02d:%02d", now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE))
        paint.textSize = 14f * dip
        val cw = paint.measureText(clock)
        paint.textSize = 11f * dip
        val pct = paint.measureText("$battLevel%")
        paint.textSize = 14f * dip
        return 2 * padX + cw + 9f * dip + 26f * dip + pct
    }

    override fun onDraw(canvas: Canvas) {
        layoutPills(width)
        drawPill(canvas, wakeBox)
        drawPill(canvas, statusBox)
        drawPill(canvas, gearBox)
        paint.typeface = font

        // ---- wake pill: ring + label, group centered ----
        val wakeLabel = if (wakeOn) "Wake" else "Wake off"
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 14f * dip
        val ringW = paint.measureText(if (wakeOn) "\u25C9" else "\u25CE")
        val gap1 = 6f * dip
        val groupW = ringW + gap1 + paint.measureText(wakeLabel)
        var x = wakeBox.centerX() - groupW / 2f
        val base1 = baseY(wakeBox, 14f * dip)
        paint.color = if (wakeOn) teal else dim
        canvas.drawText(if (wakeOn) "\u25C9" else "\u25CE", x, base1, paint)
        x += ringW + gap1
        canvas.drawText(wakeLabel, x, base1, paint)

        // ---- status pill: clock (green) + battery + percent, centered ----
        paint.textSize = 14f * dip
        val now = java.util.Calendar.getInstance()
        val clock = String.format("%02d:%02d", now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE))
        val clockW = paint.measureText(clock)
        val bw = 22f * dip
        val gap2 = 9f * dip
        val pct = if (battLevel >= 0) "$battLevel%" else "100%"
        paint.textSize = 11f * dip
        val pctW = paint.measureText(pct)
        paint.textSize = 14f * dip
        val group2 = clockW + gap2 + bw + 2f * dip + 3f * dip + pctW
        var x2 = statusBox.centerX() - group2 / 2f
        val base2 = baseY(statusBox, 14f * dip)
        paint.color = teal
        canvas.drawText(clock, x2, base2, paint)
        x2 += clockW + gap2
        drawBattery(canvas, x2, base2)   // icon vertically centered on baseline mid
        x2 += bw + 2f * dip + 3f * dip
        paint.textSize = 11f * dip
        paint.color = battColor()
        canvas.drawText(pct, x2, baseY(statusBox, 11f * dip), paint)
        paint.textSize = 14f * dip

        // ---- gear pill: centered glyph, green ----
        paint.textSize = 17f * dip
        paint.textAlign = Paint.Align.LEFT
        paint.color = teal
        val gw = paint.measureText("\u2699")
        canvas.drawText("\u2699", gearBox.centerX() - gw / 2f, baseY(gearBox, 17f * dip), paint)
        paint.textAlign = Paint.Align.LEFT

        // ---- pairing banner: centered amber pill below the pills ----
        pairText?.let { txt ->
            paint.textSize = 14f * dip
            val tw = paint.measureText(txt) + 2 * padX
            val y0 = wakeBox.bottom + 8f * dip
            val box = RectF((width - tw) / 2f, y0, (width + tw) / 2f, y0 + h)
            paint.color = 0xF2302308.toInt()
            canvas.drawRoundRect(box, pillR, pillR, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.4f * dip
            paint.color = 0x99E6A23C.toInt()
            canvas.drawRoundRect(box, pillR, pillR, paint)
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 1f
            paint.color = amber
            canvas.drawText(txt, box.centerX() - (tw - 2 * padX) / 2f, baseY(box, 14f * dip), paint)
            paint.textSize = 14f * dip
        }
    }

    // text baseline so the glyph block sits vertically centered inside `box`
    private fun baseY(box: RectF, textSize: Float): Float {
        paint.textSize = textSize
        val fm = paint.fontMetrics
        val mid = (fm.ascent + fm.descent) / 2f
        return box.centerY() - mid
    }

    private fun battColor(): Int = when {
        battCharge -> teal
        battLevel in 0..25 -> Color.parseColor("#F25F5C")   // firmware kRed
        battLevel in 26..50 -> Color.parseColor("#F2C94C")  // firmware kYellow
        else -> teal
    }

    // icon centered on the text baseline mid (ascent..descent band)
    private fun drawBattery(canvas: Canvas, x: Float, base: Float) {
        val w = 22f * dip
        val hh = 10f * dip
        val fm = paint.fontMetrics
        val cy = base + (fm.ascent + fm.descent) / 2f
        val bx = x
        val by = cy - hh / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * dip
        paint.color = dim
        canvas.drawRoundRect(bx, by, bx + w, by + hh, 2f * dip, 2f * dip, paint)
        canvas.drawRect(bx + w, by + hh * 0.3f, bx + w + 2f * dip, by + hh * 0.7f, paint)
        paint.style = Paint.Style.FILL
        if (battLevel >= 0) {
            val frac = battLevel.coerceIn(0, 100) / 100f
            paint.color = battColor()
            if (frac > 0) canvas.drawRoundRect(bx + 1.5f * dip, by + 1.5f * dip,
                bx + w * frac, by + hh - 1.5f * dip, 1f * dip, 1f * dip, paint)
        }
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 1f
    }

    private fun drawPill(canvas: Canvas, r: RectF) {
        paint.color = pillBg
        canvas.drawRoundRect(r, pillR, pillR, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = pillEdge
        canvas.drawRoundRect(r, pillR, pillR, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = when {
                    wakeBox.contains(event.x, event.y) -> 1
                    gearBox.contains(event.x, event.y) -> 2
                    else -> 0
                }
                return pressed != 0
            }
            MotionEvent.ACTION_UP -> {
                if (pressed != 0) {
                    val inside = when (pressed) {
                        1 -> wakeBox.contains(event.x, event.y)
                        else -> gearBox.contains(event.x, event.y)
                    }
                    if (inside) {
                        if (pressed == 1) onWake() else onGear()
                        invalidate()
                    }
                    pressed = 0
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> pressed = 0
        }
        return false
    }
}

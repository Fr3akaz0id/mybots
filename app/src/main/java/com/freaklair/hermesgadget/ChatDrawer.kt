package com.freaklair.hermesgadget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Bottom text drawer: swipe up (or tap the handle) to read what the agent
 * said as a chat. Bubbles are Telegram-style — agent left, you right. The
 * history lives in memory only, last 3 turns per profile, on purpose: this
 * is voice-first, the drawer is for re-reading, not for archives.
 */
class ChatDrawer(context: Context) : FrameLayout(context) {

    data class Turn(val user: String, val agent: String)

    // engine id -> last turns (oldest first); the open streaming agent text
    // lives in the last turn's buffer until finalize.
    private val history = HashMap<String, MutableList<Turn>>()
    private val maxTurns = 3

    private var open = false
    private var currentProfile: Profile? = null
    private val density get() = resources.displayMetrics.density
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val panel = LinearLayout(context)
    private val title = TextView(context)
    private val scroller = ScrollView(context)
    private val bubbles = LinearLayout(context)
    private lateinit var handle: View   // grabber strip, closes when open / opens when closed
    private val agentBg: GradientDrawable
    private val userBg: GradientDrawable

    // panel state: 0 closed .. 1 open, animated
    private var state = 0f
    // frame accent = the profile's head color, so the sheet matches the bot
    private var frameColor = 0
    // strip height in PagerView is 34 dp; keep them in sync or the overlap returns.
    // The handle floats just above the strip as a permanent tab; the panel docks
    // above the handle, so nothing ever covers the bot names.
    private val stripClear = dp(34f) + dp(10f)
    private val panelClear = stripClear + dp(28f)

    init {
        setWillNotDraw(false)
        agentBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1C1C22"))
            cornerRadius = dp(16f)
        }
        userBg = GradientDrawable().apply {
            setColor(Color.parseColor("#3A3122"))
            cornerRadius = dp(16f)
        }

        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Color.parseColor("#E6101014"))
        val rad = dp(18f)
        panel.background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f)
            setColor(Color.parseColor("#E6101014"))
            setStroke(dp(1.5f).toInt(), Color.parseColor("#2A2A33"))
        }
        val panelLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT)
        panelLp.gravity = Gravity.BOTTOM
        panelLp.bottomMargin = panelClear.toInt()
        panel.layoutParams = panelLp

        title.setTextColor(Color.parseColor("#E8C34A"))
        title.textSize = 13f
        title.setPadding(dp(14f).toInt(), dp(6f).toInt(), dp(14f).toInt(), 0)
        title.setTypeface(Typeface.DEFAULT_BOLD)
        panel.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))

        bubbles.orientation = LinearLayout.VERTICAL
        bubbles.setPadding(dp(12f).toInt(), dp(4f).toInt(), dp(12f).toInt(), dp(10f).toInt())
        scroller.addView(bubbles, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        scroller.isVerticalScrollBarEnabled = false
        val sc = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (dp(300f)).toInt())
        sc.topMargin = dp(4f).toInt()
        panel.addView(scroller, sc)

        handle = object : View(context) {
            private var downY = 0f
            override fun onDraw(c: Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (frameColor != 0) frameColor else Color.parseColor("#4A4A55")
                    alpha = if (frameColor != 0) 255 else 200
                }
                c.drawRoundRect(w / 2 - dp(18f), h / 2 - dp(2.5f), w / 2 + dp(18f), h / 2 + dp(2.5f),
                        dp(2.5f), dp(2.5f), p)
                if (state < 0.98f) {
                    val g = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(0f, 0f, 0f, h,
                                Color.parseColor("#101014"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                        alpha = ((1f - state) * 120).toInt()
                    }
                    c.drawRect(0f, 0f, w, h, g)
                }
            }
            override fun onTouchEvent(e: MotionEvent): Boolean = when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.y; true }
                MotionEvent.ACTION_UP -> {
                    val dy = e.y - downY
                    if (dy < -dp(30f)) open()
                    else if (dy > dp(30f)) close()
                    else performClick()
                    true
                }
                else -> true
            }
            override fun performClick(): Boolean { super.performClick(); if (open) close() else open(); return true }
        }
        handle.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(38f).toInt()).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = (stripClear - dp(12f)).toInt()
        }

        addView(panel, panelLp)
        addView(handle)
        visibility = GONE
    }

    // --- public API ---------------------------------------------------------

    fun bind(profile: Profile?) {
        currentProfile = profile
        if (profile == null) { visibility = GONE; return }
        visibility = if (profile.showText) VISIBLE else GONE
        title.text = "Chat — " + profile.name
        frameColor = try { Color.parseColor(profile.color) } catch (e: Exception) { 0 }
        (panel.background as? GradientDrawable)?.setStroke(
            dp(1.5f).toInt(), if (frameColor != 0) frameColor else Color.parseColor("#2A2A33"))
        title.setTextColor(if (frameColor != 0) frameColor else Color.parseColor("#E8C34A"))
        render(); handle.invalidate()
    }

    var onOpen: (() -> Unit)? = null

    fun isOpen(): Boolean = open

    fun open() {
        if (currentProfile?.showText == false) return
        open = true
        animateTo(1f)
        scrollToBottom()
        onOpen?.invoke()
    }

    fun close() { open = false; animateTo(0f) }

    /** Record the user line for the current turn. */
    fun turnUser(engineId: String, text: String) {
        val h = history.getOrPut(engineId) { mutableListOf() }
        trim(h)
        h.add(Turn(text, ""))
        render()
    }

    /** Live agent text during streaming; call with done=true to close the turn. */
    fun turnAgent(engineId: String, text: String, done: Boolean) {
        val n = history.getOrPut(engineId) { mutableListOf() }
        if (n.isEmpty()) n.add(Turn("", text))
        else { val t = n.removeAt(n.size - 1); n.add(Turn(t.user, text)) }
        if (done) trim(n)
        render()
        if (open) scrollToBottom()
    }

    /** The last streaming agent text finalized (reply finished). */
    fun finalizeTurn(engineId: String) {
        history[engineId]?.let { if (it.isNotEmpty()) trim(it) }
        render()
    }

    fun clearFor(engineId: String) { history.remove(engineId) }

    // --- internals ----------------------------------------------------------

    private fun trim(h: MutableList<Turn>) {
        while (h.size > maxTurns) h.removeAt(0)
    }

    private fun render() {
        bubbles.removeAllViews()
        val p = currentProfile ?: return
        val h = history[p.id] ?: return
        for (t in h) {
            if (t.user.isNotEmpty()) bubbles.addView(bubble(t.user, right = true))
            if (t.agent.isNotEmpty()) bubbles.addView(bubble(t.agent, right = false))
        }
    }

    private fun bubble(text: String, right: Boolean): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.setTextColor(Color.parseColor("#E6E6EA"))
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        tv.setTextIsSelectable(true)
        tv.background = if (right) userBg else agentBg
        tv.setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt())
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6f).toInt()
        lp.gravity = if (right) Gravity.END else Gravity.START
        lp.leftMargin = if (right) dp(44f).toInt() else 0
        lp.rightMargin = if (right) 0 else dp(44f).toInt()
        tv.layoutParams = lp
        return tv
    }

    private fun scrollToBottom() {
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    private fun animateTo(target: Float) {
        val from = state
        ValueAnimator.ofFloat(from, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                state = it.animatedValue as Float
                requestLayout()
                handle.invalidate()
            }
            start()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val h = (panel.height + panelClear).toFloat()
        panel.translationY = h * (1f - state)
        handle.translationY = 0f
    }
}

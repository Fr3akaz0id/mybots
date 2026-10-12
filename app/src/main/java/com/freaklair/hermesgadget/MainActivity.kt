package com.freaklair.hermesgadget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.freaklair.hermesgadget.drivers.*

// One live page per saved profile — all cores hot, swipe to switch agents.
// The "+" page and the gear both open the profile editor.
class MainActivity : AppCompatActivity() {

    private lateinit var profiles: ProfileStore
    private var root: FrameLayout? = null
    private var pager: PagerView? = null
    private var engines = listOf<PageEngine>()
    private var drawer: ChatDrawer? = null
    // idle-sleep: face goes to ZZZ and the backlight dims after this much
    // inactivity — the screen is the battery hog, not our code.
    private val idleMs = 120_000L
    private var lastActivity = System.currentTimeMillis()
    private var dimmed = false
    private val promptDialogs = HashMap<String, android.app.AlertDialog>()
    private class CoreFeed { var user = ""; var body = ""; var active = false }
    private val feeds = HashMap<String, CoreFeed>()
    private var micAsked = false
    private val wake = lazy { WakeDetector(this) }

    // MyBots: robot-head visualization + 300 ms state poll. The head is the
    // visual, PagerView keeps touch/strip. WebView sits under the pager.
    private var head: HeadView? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var resumed = false
    @Volatile private var happyUntil = 0L
    private var lastStateSig = ""
    private val headPoll = object : Runnable {
        override fun run() {
            try {
                val r = root
                if (r != null) {
                    val hv = head
                    val w0 = if (hv != null && hv.width > 0) hv.width else r.width
                    val h0 = if (hv != null && hv.height > 0) hv.height else r.height
                    head?.setBox(minOf(w0, h0) * 0.55f)
                }
                applyHeadConfig()
                val cur = pager?.currentPage ?: 0
                val eng = if (cur < engines.size) engines.getOrNull(cur) else null
                val idleFor = System.currentTimeMillis() - lastActivity
                val coreBusy = eng?.lastUi?.screen in setOf("thinking", "responding")
                val busy = (eng?.micActive() == true) || (eng?.speakingNow() == true) || coreBusy ||
                    promptDialogs.isNotEmpty() || System.currentTimeMillis() < happyUntil
                if (System.currentTimeMillis() < happyUntil) {
                    head?.setState(HeadState.HAPPY)
                } else if (idleFor > idleMs && !busy) {
                    head?.setState(HeadState.SLEEPING)
                } else {
                    val pm = getSystemService(android.os.PowerManager::class.java)
                    val screenOn = resumed && (pm?.isInteractive ?: true)
                    val next = HeadState.derive(eng, screenOn)
                    head?.setState(next)
                    val ui = eng?.lastUi
                    val sig = "${eng?.id}:$next/${ui?.screen ?: "∅"}/${ui?.status ?: ""}"
                    if (sig != lastStateSig) {
                        lastStateSig = sig
                        android.util.Log.i("headstate", sig)
                    }
                }
                if (resumed) setDim(idleFor > idleMs && !busy)
                maybePairBanner(eng)
                feedAndPrompts()
            } catch (_: Exception) { }
            ui.postDelayed(this, 300)
        }
    }

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        maybeStart()
    }

    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        previewHold = false
        headSideLayout(false)
        rebuild()  // profiles changed — pages follow the list
        applyHeadConfig(force = true)  // close discards any live preview
    }

    // head look follows the active profile; pushed once per change. While the
    // settings editor is open we hold off, so previews of a non-active profile
    // aren't clobbered by the 300ms poll.
    private var appliedCfg: Pair<String, String>? = null
    @Volatile private var previewHold = false

    // The core keeps the hub's pairing code in its status json; with the head
    // replacing the panel nobody would ever see it. Surface it in the HUD.
    private var lastPairCheck = 0L
    private var lastPairText: String? = null
    private var lastPairCode = ""
    private fun maybePairBanner(eng: PageEngine?) {
        val now = System.currentTimeMillis()
        if (now - lastPairCheck < 1500) return
        lastPairCheck = now
        var text: String? = null
        try {
            val j = org.json.JSONObject(eng?.status() ?: "{}")
            if (j.optString("phase") == "online" && !j.optBoolean("paired", true)) {
                val code = j.optString("pairing_code", "")
                text = if (code.isNotEmpty()) "Pair code $code" else "Waiting for pairing approval…"
                if (code.isNotEmpty() && code != lastPairCode) {
                    lastPairCode = code
                    // the full command, once per code — long, so not in the pill
                    android.widget.Toast.makeText(this@MainActivity,
                        "run: hermes pairing approve gadget $code",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            } else {
                lastPairCode = ""
            }
        } catch (_: Exception) {}
        if (text != lastPairText) {
            lastPairText = text
            hudRef?.setPairBanner(text)
        }
    }
    private fun applyHeadConfig(force: Boolean = false) {
        if (previewHold && !force) return
        // head look follows the page you're ON, not a global "active" —
        // swipe to another agent, get that agent's shape+color.
        val idx = (pager?.currentPage ?: 0).coerceIn(0, maxOf(0, engines.size - 1))
        val p = profiles.all().let { if (idx < it.size) it[idx] else profiles.active() } ?: return
        val cfg = p.shape to p.color
        if (!force && appliedCfg == cfg) return
        appliedCfg = cfg
        head?.setConfig(p.shape, p.color)
    }

    // live preview / editor signals from the settings sheet (not persisted
    // until save). `editing=true` also shifts the head left in landscape.
    private val headPreview = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val sh = i?.getStringExtra("shape") ?: return
            previewHold = i.getBooleanExtra("editing", false)
            headSideLayout(previewHold)
            head?.setConfig(sh, i.getStringExtra("color") ?: "")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profiles = ProfileStore(this)
        crashMarker()

        root = FrameLayout(this)
        root!!.setBackgroundColor(android.graphics.Color.BLACK)
        setContentView(root)

        ContextCompat.registerReceiver(this, headPreview,
            android.content.IntentFilter("com.freaklair.mybots.HEAD_PREVIEW"),
            ContextCompat.RECEIVER_NOT_EXPORTED)

        rebuild()  // builds engines + pager, starts them if the mic is granted

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            if (!micAsked) { micAsked = true; micPerm.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }

    override fun onDestroy() {
        hudRef?.stop()
        ui.removeCallbacks(headPoll)
        try { unregisterReceiver(headPreview) } catch (_: Exception) { }
        if (wake.isInitialized()) wake.value.shutdown()
        engines.forEach { it.stop() }
        super.onDestroy()
    }

    override fun onStop() {
        resumed = false
        setDim(false) // never carry our dim into another app
        getSharedPreferences("gadget", MODE_PRIVATE).edit().putBoolean("alive", false).apply()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        lastActivity = System.currentTimeMillis()
        ui.removeCallbacks(headPoll)
        ui.post(headPoll)
        pager?.poke()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The panel is the device's physical screen — rotating the phone
        // changes the VIEWPORT, not the gadget. Changing w/h would force a
        // core restart (hgsim has no live resize) and tear down the Hermes
        // connection. So panels keep their shape and the pager re-fits them.
        pager?.poke()
    }

    private fun panelSize(): Pair<Int, Int> =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 960 to 400
        else 480 to 480

    private fun rebuild() {
        engines.forEach { it.stop() }
        // prompt dialogs belong to the old engine generation — drop them with it
        promptDialogs.values.forEach { it.dismiss() }
        promptDialogs.clear()
        feeds.clear()
        val (w, h) = panelSize()
        engines = profiles.all().map { pg -> PageEngine(this, pg).also { it.width = w; it.height = h } }
        val p = PagerView(this, engines)
        p.owner = object : PagerView.Owner {
            override fun onPageChanged(index: Int) { head?.poke(); pokeActivity(); applyHeadConfig(force = true)
                drawer?.bind(engines.getOrNull(index)?.profile)
                engines.getOrNull(index)?.textUnread = false }
            override fun onSettingsRequested() { openSettings() }
        }
        p.onMotion = { x, y -> head?.pointer(x, y) }
        p.onMotionEnd = { head?.pointer(null, null) }
        pager = p
        engines.forEach { e -> e.display = object : DisplayDriver {
            override fun flush(y0: Int, y1: Int) { pager?.poke() }
        } }

        val r = root ?: return
        r.removeAllViews()
        if (head == null) head = HeadView(this)
        p.headMode = true
        r.addView(head, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        r.addView(p, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // text drawer sits above the pages, under the HUD chrome
        if (drawer == null) drawer = ChatDrawer(this)
        drawer?.let { dr ->
            dr.bind(engines.getOrNull(0)?.profile)
            dr.onOpen = { engines.getOrNull(pager?.currentPage ?: 0)?.textUnread = false }
            r.addView(dr, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val hud = HudOverlay(this)
        hud.onGear = { openSettings() }
        hud.onWake = { toggleWake() }
        hud.bandProvider = { null }  // head mode: controls to edge margin, no core band
        hud.wakeOn = prefWakeEnabled()
        hud.pairText = lastPairText   // survive rebuilds while a code is pending
        hud.start()
        hudRef = hud
        r.addView(hud, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        maybeStart()
    }

    private var hudRef: HudOverlay? = null

    // "Hey Hermes" wake-word master switch. ON keeps the mic hot for KWS
    // (privacy dot lit). OFF releases the mic and disables wake — the core's
    // own talk button still works. Persisted so it survives restarts.
    private fun prefWakeEnabled(): Boolean =
        getSharedPreferences("gadget", MODE_PRIVATE).getBoolean("wake", true)
    private fun setPrefWakeEnabled(on: Boolean) {
        getSharedPreferences("gadget", MODE_PRIVATE).edit().putBoolean("wake", on).apply()
    }

    private fun toggleWake() {
        val now = !prefWakeEnabled()
        setPrefWakeEnabled(now)
        if (wake.isInitialized()) wake.value.setEnabled(now)
        hudRef?.wakeOn = now
        hudRef?.invalidate()
    }

    // Any touch is "usage": reset the idle clock, restore brightness.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) pokeActivity()
        return super.dispatchTouchEvent(ev)
    }
    private fun pokeActivity() { lastActivity = System.currentTimeMillis() }

    private fun setDim(on: Boolean) {
        if (dimmed == on) return
        dimmed = on
        val lp = window.attributes
        lp.screenBrightness = if (on) 0.05f else -1f  // -1 = follow system setting
        window.attributes = lp
    }

    private fun openSettings() {
        settings.launch(Intent(this, SettingsActivity::class.java))
    }

    // While the settings sheet is open, the head occupies the left viewport
    // (landscape only) so shape/color taps preview beside the panel.
    private fun headSideLayout(on: Boolean) {
        val h = head ?: return
        val lp = h.layoutParams as? FrameLayout.LayoutParams ?: return
        val landscape = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
        if (on && landscape) {
            lp.width = (resources.displayMetrics.widthPixels * 0.42f).toInt()
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.START
        } else {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = 0
        }
        h.layoutParams = lp
    }

    /** Feed the chat drawer and raise mandatory yes/no prompts, per page. */
    private fun feedAndPrompts() {
        val dr = drawer ?: return
        for (e in engines) {
            val st = e.uiState()

            // A pending prompt must always be answerable — non-cancelable dialog,
            // one per page, the answering page gets focus so the answer can never
            // reach the wrong agent.
            val dlg = promptDialogs[e.id]
            if (st.pendingPrompt && dlg == null) {
                val t = if (st.promptTitle.isNotEmpty())
                    "${e.profile.name} — ${st.promptTitle}" else e.profile.name
                promptDialogs[e.id] = android.app.AlertDialog.Builder(this)
                    .setCancelable(false)
                    .setTitle(t)
                    .setMessage(st.promptText)
                    .setPositiveButton("Yes") { _, _ -> pokeActivity(); e.answerPrompt(true); promptDialogs.remove(e.id) }
                    .setNegativeButton("No") { _, _ -> pokeActivity(); e.answerPrompt(false); promptDialogs.remove(e.id) }
                    .show()
                pager?.setPage(engines.indexOf(e).coerceAtLeast(0))
            } else if (!st.pendingPrompt && dlg != null) {
                promptDialogs.remove(e.id)?.dismiss()
            }

            if (!e.profile.showText) continue
            val f = feeds.getOrPut(e.id) { CoreFeed() }
            if (st.user.isEmpty()) f.user = ""
            else if (st.user != f.user) { f.user = st.user; f.body = ""; f.active = false; dr.turnUser(e.id, st.user) }
            val streaming = st.screen == "responding" || st.screen == "thinking"
            if (st.body.isNotEmpty() && st.body != f.body && !st.body.startsWith("\"")) {
                if (!f.active) { dr.turnUser(e.id, ""); f.active = true }
                f.body = st.body
                dr.turnAgent(e.id, st.body, done = false)
                if ((pager?.currentPage ?: 0) != engines.indexOf(e) && !dr.isOpen())
                    e.textUnread = true
            }
            if (f.active && !streaming && f.body.isNotEmpty()) { f.active = false; dr.finalizeTurn(e.id) }
        }
    }

    private fun maybeStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return
        engines.forEach { if (!it.isRunning()) it.start() }
        val active = profiles.active()?.id
        val i = if (active != null) engines.indexOfFirst { it.id == active } else 0
        pager?.setPage(if (i >= 0) i else 0)
        wakeStarted()
    }

    // Keep the wake detector pointed at the current foreground page, start it once.
    private fun wakeStarted() {
        val d = wake.value
        d.setInitialState(prefWakeEnabled())
        d.start()
        d.setEnabled(prefWakeEnabled())
        // MyBots: wake match -> hop + a short happy beat, then the poller
        // returns to the derived state.
        d.onMatch = {
            lastActivity = System.currentTimeMillis()
            happyUntil = System.currentTimeMillis() + 1600L
            head?.poke()
            head?.setState(HeadState.HAPPY)
        }
        // poll the pager's current page — it owns the foreground state
        d.target = pager?.currentPage?.let { engines.getOrNull(it) }
        mainTick()
    }

    private val hnd = android.os.Handler(android.os.Looper.getMainLooper())
    private fun mainTick() {
        hnd.postDelayed({
            val d = if (wake.isInitialized()) wake.value else null
            if (d != null) {
                d.target = pager?.currentPage?.let { engines.getOrNull(it) }
                mainTick()
            }
        }, 500)
    }

    // Crash marker: set while the app is live; leftover from last run means it
    // died mid-session. One log line (the pager UI surfaces this later).
    private fun crashMarker() {
        val prefs = getSharedPreferences("gadget", Context.MODE_PRIVATE)
        if (prefs.getBoolean("alive", false)) {
            Log.w("hgmain", "previous run ended unexpectedly")
        }
        prefs.edit().putBoolean("alive", true).apply()
    }
}

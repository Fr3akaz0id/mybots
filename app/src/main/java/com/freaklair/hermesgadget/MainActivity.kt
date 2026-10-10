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
    private var micAsked = false
    private val wake = lazy { WakeDetector(this) }

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        maybeStart()
    }

    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        rebuild()  // profiles changed — pages follow the list
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profiles = ProfileStore(this)
        crashMarker()

        root = FrameLayout(this)
        root!!.setBackgroundColor(android.graphics.Color.BLACK)
        setContentView(root)

        rebuild()  // builds engines + pager, starts them if the mic is granted

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            if (!micAsked) { micAsked = true; micPerm.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }

    override fun onDestroy() {
        hudRef?.stop()
        if (wake.isInitialized()) wake.value.shutdown()
        engines.forEach { it.stop() }
        super.onDestroy()
    }

    override fun onStop() {
        getSharedPreferences("gadget", MODE_PRIVATE).edit().putBoolean("alive", false).apply()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
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
        val (w, h) = panelSize()
        engines = profiles.all().map { pg -> PageEngine(this, pg).also { it.width = w; it.height = h } }
        val p = PagerView(this, engines)
        p.owner = object : PagerView.Owner {
            override fun onPageChanged(index: Int) { }
            override fun onSettingsRequested() { openSettings() }
        }
        pager = p
        engines.forEach { e -> e.display = object : DisplayDriver {
            override fun flush(y0: Int, y1: Int) { pager?.poke() }
        } }

        val r = root ?: return
        r.removeAllViews()
        r.addView(p, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val hud = HudOverlay(this)
        hud.onGear = { openSettings() }
        hud.onWake = { toggleWake() }
        hud.bandProvider = { pager?.hudBand() }
        hud.wakeOn = prefWakeEnabled()
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

    private fun openSettings() {
        settings.launch(Intent(this, SettingsActivity::class.java))
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

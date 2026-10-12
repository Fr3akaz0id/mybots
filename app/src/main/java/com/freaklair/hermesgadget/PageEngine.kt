package com.freaklair.hermesgadget

import android.content.Context
import com.freaklair.hermesgadget.drivers.*
import java.util.concurrent.LinkedBlockingQueue

// A page = one agent = one profile = one core instance on its own thread.
// All pages run hot (sockets stay connected, hub sees all devices online);
// foreground ownership only decides display + audio:
//  - foreground: 100 Hz loop, owns mic, owns speaker
//  - background: ~10 Hz heartbeats, mic refused, speaker samples dropped
class PageEngine(private val context: Context, val profile: Profile) {

    val host = NativeHost()
    val id: String = profile.id
    var name: String = profile.name
    var foreground = false
        private set
    val hasNotified: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile var muted = false

    private val queue = LinkedBlockingQueue<() -> Unit>()
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var ptr = 0L
        private set

    @Volatile var display: DisplayDriver? = null
    var width = 480
    var height = 480

    // Head-state bookkeeping (MyBots): transport liveness + last talk release.
    @Volatile var wsOpen = false
        private set
    @Volatile var closeFails = 0
    @Volatile var lastTouchUpAt = 0L

    private val prefs = context.getSharedPreferences("gadget", Context.MODE_PRIVATE)

    private val transport = WsTransport(
        onOpen = { wsOpen = true; closeFails = 0; post { if (ptr != 0L) NativeBridge.nativeTransportOpen(ptr) } },
        onText = { s -> post { if (ptr != 0L) NativeBridge.nativeTransportText(ptr, s) } },
        onBinary = { b -> post { if (ptr != 0L) NativeBridge.nativeTransportBinary(ptr, b) } },
        onClose = { r -> wsOpen = false; closeFails++; post { if (ptr != 0L) NativeBridge.nativeTransportClosed(ptr, r) } },
    )

    private val mic = MicDriver(context, { foreground && !muted }) { samples -> post { if (ptr != 0L) NativeBridge.nativeMicSamples(ptr, samples) } }
    private val speaker = SpeakerDriver(context) { foreground && !muted }
    fun micActive(): Boolean = mic.busy
    fun speakingNow(): Boolean = speaker.streaming || speaker.busy()
    fun lastAudioAt(): Long = speaker.audioAt
    // Namespace the name key per profile so page A's core can't overwrite
    // page B's identity in the shared prefs (device_key stays global on
    // purpose — one device identity, many hubs).
    private val storage = PrefStorage(prefs, mapOf("name" to "name__${profile.id}"))

    init {
        host.transport = transport
        host.mic = mic
        host.speaker = speaker
        host.storage = storage
        host.display = object : DisplayDriver {
            override fun flush(y0: Int, y1: Int) {
                // any content change while backgrounded = unread; there is no
                // dedicated notify frame — a flush IS activity (cards, answers)
                if (!foreground) hasNotified.set(true)
                display?.flush(y0, y1)
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "hgcore-$id").also { it.isDaemon = true; it.start() }
    }

    fun setForeground(on: Boolean) {
        foreground = on
        if (!on) {
            // release the phone's audio for whoever owns it now
            try { mic.stop() } catch (_: Exception) {}
            try { speaker.abort() } catch (_: Exception) {}
        } else {
            hasNotified.set(false)  // this page is now on screen
        }
    }

    fun stop() {
        running = false
        thread?.let { try { it.join(500) } catch (_: InterruptedException) {} }
        thread = null
        mic.stop()
        speaker.abort()
        if (ptr != 0L) { NativeBridge.nativeDestroy(ptr); ptr = 0L }
    }

    private fun loop() {
        createCore()
        while (running) {
            var guard = 0
            while (guard++ < 64) {
                val item = queue.poll() ?: break
                try { item() } catch (e: Exception) { }
            }
            if (ptr != 0L) NativeBridge.nativeTick(ptr)
            try { Thread.sleep(if (foreground) 10L else 100L) } catch (_: InterruptedException) { break }
        }
    }

    // The core's 5x7 font maps every non-ASCII byte to '?', so a name like
    // "laptop · Hermes" draws as "laptop ?? Hermes". Core-visible name is
    // ASCII-clean; the pager strip keeps the pretty one.
    private fun coreName(s: String): String =
        s.replace(Regex("[^\\x20-\\x7E]"), "-").trim().ifEmpty { "Android Gadget" }

    private fun createCore() {
        // Announce ourselves as `MyBots <phone> (<page>)`: one app, one per
        // phone, the page says which persona face this phone brought. Pairing
        // approvals then read as a device act, never "a bot paired itself".
        val label = helloLabel(context, name)
        // Pre-seed NVS: core reads setting("name", default), and an old cached
        // non-ASCII value would win over our default otherwise.
        storage.set("name", coreName(label))
        ptr = NativeBridge.nativeCreateOn(host, profile.url, profile.token, width, height,
            1, 1, 16000L, 16000L, coreName(label))
        if (ptr != 0L) NativeBridge.nativeNetwork(ptr, if (netUp()) 1 else 0)
    }

    private fun netUp(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isRunning(): Boolean = running

    fun restart() {
        stop()
        start()
    }

    fun post(block: () -> Unit) { queue.offer(block) }

    fun touch(touching: Boolean, x: Int, y: Int) {
        if (!touching) lastTouchUpAt = System.currentTimeMillis()
        post { if (ptr != 0L) NativeBridge.nativeTouch(ptr, if (touching) 1 else 0, x, y) }
    }

    fun status(): String = try { if (ptr != 0L) NativeBridge.nativeStatus(ptr) else "{}" } catch (e: Exception) { "{}" }

    fun size(): Pair<Int, Int> {
        val d = try { if (ptr != 0L) NativeBridge.nativeSize(ptr) else intArrayOf(0, 0) } catch (e: Exception) { intArrayOf(0, 0) }
        return d[0] to d[1]
    }

    fun rows(y0: Int, y1: Int): Array<IntArray> =
        if (ptr == 0L) emptyArray() else NativeBridge.nativeRows(ptr, y0, y1)

    companion object {
        /** This phone's public name: user override, else the system device
         *  name, else the model. Tap "Phone name" in Settings to change. */
        fun deviceName(context: android.content.Context): String {
            val pref = context.getSharedPreferences("gadget", android.content.Context.MODE_PRIVATE)
                .getString("device_name", "")
            if (!pref.isNullOrBlank()) return pref.trim()
            val sys = android.provider.Settings.Global.getString(context.contentResolver, "device_name")
            if (!sys.isNullOrBlank()) return sys.trim()
            return android.os.Build.MODEL
        }

        /** The hello string hubs register: `MyBots <phone> (<page>)`. */
        fun helloLabel(context: android.content.Context, pageName: String): String =
            "MyBots ${deviceName(context)} ($pageName)"
    }
}

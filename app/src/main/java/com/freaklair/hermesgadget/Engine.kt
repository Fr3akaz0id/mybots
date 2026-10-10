package com.freaklair.hermesgadget

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.freaklair.hermesgadget.drivers.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// Owns the core thread: the only thread allowed to touch NativeBridge. All
// inputs queue; the loop drains them and ticks the core at 100 Hz, mirroring
// Simulator.step() in python/sim/runner.py.
class Engine(private val context: Context) {

    val host = NativeHost()
    private val queue = LinkedBlockingQueue<() -> Unit>()
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var ptr = 0L
        private set

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gadget", Context.MODE_PRIVATE)

    private val transport = WsTransport(
        onOpen = { post { NativeBridge.nativeTransportOpen(ptr) } },
        onText = { s -> post { NativeBridge.nativeTransportText(ptr, s) } },
        onBinary = { b -> post { NativeBridge.nativeTransportBinary(ptr, b) } },
        onClose = { r -> post { NativeBridge.nativeTransportClosed(ptr, r) } },
    )

    private val mic = MicDriver(context, { true }) { samples -> post { NativeBridge.nativeMicSamples(ptr, samples) } }
    private val speaker = SpeakerDriver(context) { true }
    private val storage = PrefStorage(prefs)

    @Volatile var display: DisplayDriver? = null // MainActivity sets this before start()

    var width = 480
    var height = 480
        private set

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    init {
        host.transport = transport
        host.mic = mic
        host.speaker = speaker
        host.storage = storage
        host.display = object : DisplayDriver {
            override fun flush(y0: Int, y1: Int) { display?.flush(y0, y1) }
        }
        NativeHost.INSTANCE = host
    }

    fun start(url: String, token: String, name: String, surfaceW: Int, surfaceH: Int) {
        stop()
        this.url = url; this.token = token; this.name = name
        this.width = surfaceW; this.height = surfaceH
        running = true
        thread = Thread({ loop() }, "hgcore").also { it.start() }
        registerNetwork()
    }
    private var url = ""
    private var token = ""
    private var name = "Android Gadget"

    private fun createCore() {
        // The core's single-thread contract: create+begin on the loop thread.
        ptr = NativeBridge.nativeCreateOn(host, url, token, width, height, 1, 1, 16000L, 16000L, name)
        if (ptr != 0L) {
            NativeBridge.nativeNetwork(ptr, if (netUp()) 1 else 0)
        }
    }

    fun stop() {
        if (!running && ptr == 0L) return
        running = false
        thread?.let { try { it.join(500) } catch (_: InterruptedException) {} }
        thread = null
        unregisterNetwork()
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
                try { item() } catch (e: Exception) { Log.w("hgcore", "event failed", e) }
            }
            if (ptr != 0L) NativeBridge.nativeTick(ptr)
            try { Thread.sleep(10) } catch (_: InterruptedException) { break }
        }
    }

    fun post(block: () -> Unit) { queue.offer(block) }

    fun touch(touching: Boolean, x: Int, y: Int) =
        post { NativeBridge.nativeTouch(ptr, if (touching) 1 else 0, x, y) }

    fun submitText(text: String) = post { NativeBridge.nativeSubmitText(ptr, text) }
    fun button(b: Int, pressed: Boolean) =
        post { NativeBridge.nativeButton(ptr, b, if (pressed) 1 else 0) }

    fun status(): String = try { NativeBridge.nativeStatus(ptr) } catch (e: Exception) { "{}" }
    fun screen(): String = try { NativeBridge.nativeScreen(ptr) } catch (e: Exception) { "" }
    fun console(line: String): String = NativeBridge.nativeConsole(ptr, line)
    fun size(): Pair<Int, Int> {
        val d = try { NativeBridge.nativeSize(ptr) } catch (e: Exception) { intArrayOf(0, 0) }
        return d[0] to d[1]
    }

    // --- connectivity ---------------------------------------------------------

    private fun netUp(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { post { NativeBridge.nativeNetwork(ptr, 1) } }
            override fun onLost(network: Network) { post { NativeBridge.nativeNetwork(ptr, 0) } }
        }
        netCallback = cb
        try { cm.registerDefaultNetworkCallback(cb) } catch (e: Exception) { Log.w("hgcore", "net cb failed", e) }
    }

    private fun unregisterNetwork() {
        netCallback?.let { try { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCallback = null
    }
}
